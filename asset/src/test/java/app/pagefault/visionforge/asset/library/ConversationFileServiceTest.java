package app.pagefault.visionforge.asset.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

class ConversationFileServiceTest {

    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e34");
    private static final UUID CONVERSATION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID OTHER_CONVERSATION_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e31");
    private static final UUID FILE_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e35");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private ConversationFileMapper fileMapper;
    private ConversationFileVersionMapper versionMapper;
    private ConversationReferenceChecker conversationChecker;
    private ConversationFileService service;

    @BeforeEach
    void setUp() {
        fileMapper = mock(ConversationFileMapper.class);
        versionMapper = mock(ConversationFileVersionMapper.class);
        conversationChecker = mock(ConversationReferenceChecker.class);
        IdGenerator idGenerator = UUID::randomUUID;
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ConversationFileService(
                fileMapper, versionMapper, conversationChecker, idGenerator, clock);
    }

    @Test
    void createsFileInsideTheConversationWorkArea() {
        when(fileMapper.selectCount(any())).thenReturn(0L);

        ConversationFile file = service.create(
                USER_ID,
                CONVERSATION_ID,
                new CreateConversationFileCommand(
                        ConversationFileKind.SCRIPT,
                        "第一幕",
                        "scripts/act-1.md",
                        "# Act 1",
                        ConversationFileSource.AGENT));

        assertThat(file.getConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(file.getUserId()).isEqualTo(USER_ID);
        assertThat(file.getCurrentVersion()).isEqualTo(1);

        ArgumentCaptor<ConversationFileVersion> versionCaptor =
                ArgumentCaptor.forClass(ConversationFileVersion.class);
        verify(versionMapper).insert(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getContent()).isEqualTo("# Act 1");
        assertThat(versionCaptor.getValue().getSource()).isEqualTo(ConversationFileSource.AGENT);
    }

    @Test
    void rejectsConversationOwnedByAnotherUser() {
        org.mockito.Mockito.doThrow(new BusinessException(AssetErrorCode.CONVERSATION_NOT_FOUND))
                .when(conversationChecker).requireOwned(any(), any());

        assertThatThrownBy(() -> service.list(USER_ID, CONVERSATION_ID, null, 10))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AssetErrorCode.CONVERSATION_NOT_FOUND));

        verify(fileMapper, never()).insert(any(ConversationFile.class));
    }

    @Test
    void rejectsFileFromAnotherConversation() {
        ConversationFile file = file(OTHER_CONVERSATION_ID, 1);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);

        assertThatThrownBy(() -> service.get(USER_ID, CONVERSATION_ID, FILE_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AssetErrorCode.FILE_NOT_FOUND));
    }

    @Test
    void rejectsUpdateWithStaleBaseVersion() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(CONVERSATION_ID, 2));

        assertThatThrownBy(() -> service.update(
                USER_ID,
                CONVERSATION_ID,
                FILE_ID,
                new UpdateConversationFileCommand(1, "new content", null, null)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AssetErrorCode.FILE_VERSION_CONFLICT));

        verify(versionMapper, never()).insert(any(ConversationFileVersion.class));
    }

    @Test
    void rejectsMissingOrCrossConversationFileVersion() {
        UUID versionId = UUID.randomUUID();
        when(versionMapper.selectById(versionId)).thenReturn(null);

        assertThatThrownBy(() -> service.requireVersionInConversation(
                USER_ID, CONVERSATION_ID, versionId, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AssetErrorCode.FILE_VERSION_NOT_FOUND));

        ConversationFileVersion version = new ConversationFileVersion();
        version.setId(versionId);
        version.setFileId(FILE_ID);
        version.setAssetId(UUID.randomUUID());
        when(versionMapper.selectById(versionId)).thenReturn(version);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(OTHER_CONVERSATION_ID, 1));

        assertThatThrownBy(() -> service.requireVersionInConversation(
                USER_ID, CONVERSATION_ID, versionId, version.getAssetId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AssetErrorCode.FILE_NOT_FOUND));
    }

    private ConversationFile file(UUID conversationId, int currentVersion) {
        ConversationFile file = new ConversationFile();
        file.setId(FILE_ID);
        file.setConversationId(conversationId);
        file.setUserId(USER_ID);
        file.setKind(ConversationFileKind.SCRIPT);
        file.setTitle("Script");
        file.setCurrentVersion(currentVersion);
        file.setCreatedAt(NOW);
        file.setUpdatedAt(NOW);
        return file;
    }
}
