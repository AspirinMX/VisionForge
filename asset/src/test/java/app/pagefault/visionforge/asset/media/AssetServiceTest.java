package app.pagefault.visionforge.asset.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.storage.ObjectMetadata;
import app.pagefault.visionforge.asset.storage.ObjectStorage;
import app.pagefault.visionforge.asset.storage.PresignedUrl;
import app.pagefault.visionforge.asset.storage.StorageProperties;
import app.pagefault.visionforge.asset.storage.StoredObject;
import app.pagefault.visionforge.asset.upload.CreateUploadCommand;
import app.pagefault.visionforge.asset.upload.UploadSession;
import app.pagefault.visionforge.asset.upload.UploadSessionMapper;
import app.pagefault.visionforge.asset.upload.UploadSessionStatus;
import app.pagefault.visionforge.asset.upload.UploadTicket;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

class AssetServiceTest {

    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e34");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID ASSET_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e35");
    private static final UUID SESSION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e36");
    private static final String OBJECT_KEY = "users/" + USER_ID + "/conversations/"
            + CONVERSATION_ID + "/uploads/" + SESSION_ID + "/photo.png";
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private AssetMapper assetMapper;
    private UploadSessionMapper uploadSessionMapper;
    private ConversationReferenceChecker conversationChecker;
    private ObjectStorage storage;
    private AssetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        assetMapper = mock(AssetMapper.class);
        uploadSessionMapper = mock(UploadSessionMapper.class);
        conversationChecker = mock(ConversationReferenceChecker.class);
        storage = mock(ObjectStorage.class);
        ObjectProvider<ObjectStorage> storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(storage.backend()).thenReturn(
                app.pagefault.visionforge.asset.storage.StorageBackend.S3);

        StorageProperties properties = new StorageProperties(
                "s3",
                "http://localhost:9000",
                null,
                "us-east-1",
                "visionforge",
                "key",
                "secret",
                true,
                Duration.ofMinutes(15),
                Duration.ofHours(1),
                10_000L);
        IdGenerator idGenerator = new SequenceIdGenerator(ASSET_ID, SESSION_ID);
        service = new AssetService(
                assetMapper,
                uploadSessionMapper,
                conversationChecker,
                storageProvider,
                properties,
                idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listsConversationAssetsAfterTheOwnershipCheck() {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setConversationId(CONVERSATION_ID);
        when(assetMapper.selectList(any())).thenReturn(List.of(asset));

        List<Asset> result = service.list(
                USER_ID, CONVERSATION_ID, AssetKind.IMAGE, AssetSource.UPLOAD, 500);

        assertThat(result).containsExactly(asset);
        verify(conversationChecker).requireOwned(USER_ID, CONVERSATION_ID);
    }

    @Test
    void filterOwnedSkipsTheQueryForAnEmptyCandidateList() {
        assertThat(service.filterOwned(USER_ID, CONVERSATION_ID, List.of())).isEmpty();
        assertThat(service.filterOwned(USER_ID, CONVERSATION_ID, null)).isEqualTo(Set.of());
        verify(assetMapper, never()).selectList(any());
    }

    @Test
    void createsAnUploadSessionWithAServerAllocatedKey() {
        when(storage.presignPut(anyString(), any(), anyString())).thenReturn(new PresignedUrl(
                URI.create("https://storage.example.com/put"),
                NOW.plus(Duration.ofMinutes(15)),
                Map.of("Content-Type", "image/png")));

        UploadTicket ticket = service.createUpload(
                USER_ID,
                CONVERSATION_ID,
                new CreateUploadCommand(AssetKind.IMAGE, "photo.png", "image/png", 1024));

        assertThat(ticket.assetId()).isEqualTo(ASSET_ID);
        assertThat(ticket.uploadSessionId()).isEqualTo(SESSION_ID);
        assertThat(ticket.uploadUrl()).isEqualTo(URI.create("https://storage.example.com/put"));
        assertThat(ticket.requiredHeaders()).containsEntry("Content-Type", "image/png");

        ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
        verify(assetMapper).insert(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getStatus()).isEqualTo(AssetStatus.PENDING);
        assertThat(assetCaptor.getValue().getObjectKey())
                .isEqualTo("users/" + USER_ID + "/conversations/" + CONVERSATION_ID
                        + "/uploads/" + SESSION_ID + "/photo.png");

        ArgumentCaptor<UploadSession> sessionCaptor =
                ArgumentCaptor.forClass(UploadSession.class);
        verify(uploadSessionMapper).insert(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getStatus()).isEqualTo(UploadSessionStatus.CREATED);
        assertThat(sessionCaptor.getValue().getExpectedSize()).isEqualTo(1024);
        verify(conversationChecker).requireOwned(USER_ID, CONVERSATION_ID);
    }

    @Test
    void rejectsConversationsOwnedByAnotherUser() {
        doThrow(new BusinessException(AssetErrorCode.CONVERSATION_NOT_FOUND))
                .when(conversationChecker).requireOwned(any(), any());

        assertThatThrownBy(() -> service.createUpload(
                USER_ID,
                CONVERSATION_ID,
                new CreateUploadCommand(AssetKind.IMAGE, "photo.png", "image/png", 1024)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AssetErrorCode.CONVERSATION_NOT_FOUND));

        verify(assetMapper, never()).insert(any(Asset.class));
        verify(storage, never()).presignPut(anyString(), any(), anyString());
    }

    @Test
    void completesTheUploadAfterVerifyingTheObject() {
        UploadSession session = session(UploadSessionStatus.CREATED, 1024, "image/png");
        Asset asset = asset(AssetStatus.PENDING);
        when(uploadSessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset);
        when(storage.head(OBJECT_KEY)).thenReturn(new ObjectMetadata(
                OBJECT_KEY, 1024, "image/png", "etag", "staging-checksum", NOW));
        String finalKey = "users/" + USER_ID + "/conversations/" + CONVERSATION_ID
                + "/assets/" + ASSET_ID + "/original.png";
        when(storage.copy(OBJECT_KEY, finalKey, "image/png")).thenReturn(
                new StoredObject(finalKey, 1024, "etag", "checksum"));

        Asset result = service.completeUpload(USER_ID, CONVERSATION_ID, SESSION_ID);

        assertThat(result.getStatus()).isEqualTo(AssetStatus.READY);
        assertThat(result.getObjectKey()).isEqualTo(finalKey);
        assertThat(result.getChecksum()).isEqualTo("checksum");
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.VERIFIED);
        verify(assetMapper).updateById(asset);
        verify(uploadSessionMapper).updateById(session);
    }

    @Test
    void rejectsAnObjectWithTheWrongSize() {
        UploadSession session = session(UploadSessionStatus.CREATED, 1024, "image/png");
        Asset asset = asset(AssetStatus.PENDING);
        when(uploadSessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset);
        when(storage.head(OBJECT_KEY)).thenReturn(new ObjectMetadata(
                OBJECT_KEY, 1000, "image/png", "etag", null, NOW));

        assertThatThrownBy(() ->
                service.completeUpload(USER_ID, CONVERSATION_ID, SESSION_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AssetErrorCode.UPLOAD_SIZE_MISMATCH));

        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.FAILED);
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.PENDING);
    }

    @Test
    void rejectsAnExpiredUploadSessionWithoutReadingTheObject() {
        UploadSession session = session(UploadSessionStatus.CREATED, 1024, "image/png");
        session.setExpiresAt(NOW.minusSeconds(1));
        Asset asset = asset(AssetStatus.PENDING);
        when(uploadSessionMapper.selectById(SESSION_ID)).thenReturn(session);
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset);

        assertThatThrownBy(() ->
                service.completeUpload(USER_ID, CONVERSATION_ID, SESSION_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AssetErrorCode.UPLOAD_SESSION_EXPIRED));

        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
        verify(storage, never()).head(anyString());
    }

    @Test
    void hidesAssetsOwnedByAnotherUser() {
        Asset asset = asset(AssetStatus.READY);
        asset.setUserId(UUID.randomUUID());
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset);

        assertThatThrownBy(() -> service.createDownloadUrl(USER_ID, ASSET_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AssetErrorCode.ASSET_NOT_FOUND));

        verify(storage, never()).presignGet(anyString(), any(), any());
    }

    private UploadSession session(UploadSessionStatus status, long size, String mimeType) {
        UploadSession session = new UploadSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setConversationId(CONVERSATION_ID);
        session.setAssetId(ASSET_ID);
        session.setKind(AssetKind.IMAGE);
        session.setBucket("visionforge");
        session.setObjectKey(OBJECT_KEY);
        session.setMimeType(mimeType);
        session.setExpectedSize(size);
        session.setStatus(status);
        session.setExpiresAt(NOW.plus(Duration.ofHours(1)));
        session.setCreatedAt(NOW);
        session.setUpdatedAt(NOW);
        return session;
    }

    private Asset asset(AssetStatus status) {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setUserId(USER_ID);
        asset.setConversationId(CONVERSATION_ID);
        asset.setKind(AssetKind.IMAGE);
        asset.setStorageBackend(
                app.pagefault.visionforge.asset.storage.StorageBackend.S3);
        asset.setBucket("visionforge");
        asset.setObjectKey(OBJECT_KEY);
        asset.setMimeType("image/png");
        asset.setSizeBytes(1024);
        asset.setSource(AssetSource.UPLOAD);
        asset.setStatus(status);
        asset.setCreatedAt(NOW);
        asset.setUpdatedAt(NOW);
        return asset;
    }

    private static final class SequenceIdGenerator implements IdGenerator {

        private final UUID[] ids;
        private int index;

        private SequenceIdGenerator(UUID... ids) {
            this.ids = ids;
        }

        @Override
        public UUID nextId() {
            return ids[index++];
        }
    }
}
