package app.pagefault.visionforge.asset.media;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.storage.ObjectKeys;
import app.pagefault.visionforge.asset.storage.ObjectMetadata;
import app.pagefault.visionforge.asset.storage.ObjectStorage;
import app.pagefault.visionforge.asset.storage.ObjectStorageException;
import app.pagefault.visionforge.asset.storage.PresignedUrl;
import app.pagefault.visionforge.asset.storage.StorageProperties;
import app.pagefault.visionforge.asset.storage.StoredObject;
import app.pagefault.visionforge.asset.upload.CreateUploadCommand;
import app.pagefault.visionforge.asset.upload.UploadSession;
import app.pagefault.visionforge.asset.upload.UploadSessionMapper;
import app.pagefault.visionforge.asset.upload.UploadSessionStatus;
import app.pagefault.visionforge.asset.upload.UploadTicket;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.SystemException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;

/**
 * Owns the binary layer of a conversation: upload sessions, assets and short-lived access URLs.
 *
 * <p>The service never talks to a vendor SDK directly; all object operations go through
 * {@link ObjectStorage}. A storage backend that is not configured makes media operations fail with
 * a clear 503 instead of preventing the whole application from starting.
 */
@RequiredArgsConstructor
public class AssetService {

    private final AssetMapper assetMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final ConversationReferenceChecker conversationReferenceChecker;
    private final ObjectProvider<ObjectStorage> storageProvider;
    private final StorageProperties storageProperties;
    private final IdGenerator idGenerator;
    private final Clock clock;

    @Transactional
    public UploadTicket createUpload(
            UUID userId, UUID conversationId, CreateUploadCommand command) {
        requireConversation(userId, conversationId);
        String mimeType = validate(command);
        ObjectStorage storage = storage();
        Instant now = clock.instant();

        UUID assetId = idGenerator.nextId();
        UUID uploadSessionId = idGenerator.nextId();
        String objectKey =
                ObjectKeys.upload(userId, conversationId, uploadSessionId, command.filename());

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setUserId(userId);
        asset.setConversationId(conversationId);
        asset.setKind(command.kind());
        asset.setStorageBackend(storage.backend());
        asset.setBucket(storageProperties.bucket());
        asset.setObjectKey(objectKey);
        asset.setMimeType(mimeType);
        asset.setSizeBytes(command.sizeBytes());
        asset.setSource(AssetSource.UPLOAD);
        asset.setStatus(AssetStatus.PENDING);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetMapper.insert(asset);

        Instant expiresAt = now.plus(storageProperties.uploadSessionTtlOrDefault());
        UploadSession session = new UploadSession();
        session.setId(uploadSessionId);
        session.setUserId(userId);
        session.setConversationId(conversationId);
        session.setAssetId(assetId);
        session.setKind(command.kind());
        session.setBucket(storageProperties.bucket());
        session.setObjectKey(objectKey);
        session.setMimeType(mimeType);
        session.setExpectedSize(command.sizeBytes());
        session.setStatus(UploadSessionStatus.CREATED);
        session.setExpiresAt(expiresAt);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        uploadSessionMapper.insert(session);

        PresignedUrl presigned = presignPut(storage, objectKey, mimeType);
        return new UploadTicket(
                uploadSessionId,
                assetId,
                presigned.url(),
                presigned.expiresAt(),
                expiresAt,
                presigned.requiredHeaders());
    }

    /**
     * Verifies the object the client uploaded and turns the asset READY. Repeating this call after
     * success returns the same asset.
     */
    @Transactional
    public Asset completeUpload(
            UUID userId, UUID conversationId, UUID uploadSessionId) {
        UploadSession session = requireSession(userId, conversationId, uploadSessionId);
        Asset asset = requireAsset(userId, session.getAssetId());

        if (session.getStatus() == UploadSessionStatus.VERIFIED) {
            return asset;
        }
        if (session.getStatus() == UploadSessionStatus.EXPIRED) {
            throw new BusinessException(AssetErrorCode.UPLOAD_SESSION_EXPIRED);
        }
        if (session.getStatus() == UploadSessionStatus.FAILED
                || session.getStatus() == UploadSessionStatus.ABORTED) {
            throw new BusinessException(AssetErrorCode.UPLOAD_SESSION_NOT_ACTIVE);
        }

        Instant now = clock.instant();
        if (!session.getExpiresAt().isAfter(now)) {
            expire(session, now);
            throw new BusinessException(AssetErrorCode.UPLOAD_SESSION_EXPIRED);
        }

        ObjectMetadata metadata = head(session.getObjectKey());
        if (metadata.sizeBytes() != session.getExpectedSize()) {
            fail(session, now);
            throw new BusinessException(AssetErrorCode.UPLOAD_SIZE_MISMATCH);
        }
        String actualType = AssetKind.normalizeMimeType(metadata.contentType());
        String expectedType = AssetKind.normalizeMimeType(session.getMimeType());
        if (!actualType.isEmpty() && !actualType.equals(expectedType)) {
            fail(session, now);
            throw new BusinessException(AssetErrorCode.UPLOAD_CONTENT_TYPE_MISMATCH);
        }

        // The presigned PUT key stays writable until its signature expires. Copy it to a final key
        // that was never handed to the client, so the READY asset is immutable.
        String finalKey = ObjectKeys.asset(
                userId,
                conversationId,
                asset.getId(),
                extensionFor(session.getMimeType()));
        StoredObject copied = copy(session.getObjectKey(), finalKey, session.getMimeType());
        if (copied.sizeBytes() != session.getExpectedSize()) {
            fail(session, now);
            throw new BusinessException(AssetErrorCode.UPLOAD_SIZE_MISMATCH);
        }

        asset.setObjectKey(finalKey);
        asset.setSizeBytes(copied.sizeBytes());
        asset.setChecksum(copied.checksum());
        asset.setStatus(AssetStatus.READY);
        asset.setUpdatedAt(now);
        assetMapper.updateById(asset);

        session.setStatus(UploadSessionStatus.VERIFIED);
        session.setUpdatedAt(now);
        uploadSessionMapper.updateById(session);
        return asset;
    }

    public Asset get(UUID userId, UUID assetId) {
        return requireAsset(userId, assetId);
    }

    public Asset getForConversation(UUID userId, UUID conversationId, UUID assetId) {
        Asset asset = requireAsset(userId, assetId);
        if (!conversationId.equals(asset.getConversationId())) {
            throw new BusinessException(AssetErrorCode.ASSET_NOT_FOUND);
        }
        return asset;
    }

    /**
     * Stores bytes produced by a generation task under the task's stable output key. The object
     * write is intentionally outside a database transaction: a retry rewrites the same key, and
     * the caller records the returned asset id before publishing a conversation file.
     */
    public Asset createGeneratedAsset(
            UUID userId,
            UUID conversationId,
            UUID taskId,
            UUID outputId,
            AssetKind kind,
            String mimeType,
            byte[] content) {
        requireConversation(userId, conversationId);
        if (taskId == null || outputId == null) {
            throw new BusinessException(AssetErrorCode.ASSET_NOT_FOUND);
        }
        if (kind == null || !kind.supports(mimeType)) {
            throw new BusinessException(AssetErrorCode.ASSET_MIME_UNSUPPORTED);
        }
        if (content == null || content.length == 0) {
            throw new BusinessException(AssetErrorCode.ASSET_SIZE_INVALID);
        }
        ObjectStorage storage = storage();
        String normalizedType = AssetKind.normalizeMimeType(mimeType);
        String objectKey = ObjectKeys.generation(
                userId,
                conversationId,
                taskId,
                outputId,
                extensionFor(normalizedType));
        StoredObject stored;
        try (java.io.InputStream input = new java.io.ByteArrayInputStream(content)) {
            stored = storage.put(new app.pagefault.visionforge.asset.storage.StoragePutRequest(
                    objectKey, normalizedType, content.length, input));
        } catch (java.io.IOException exception) {
            throw storageFailure(new ObjectStorageException(false, exception.getMessage(), exception));
        } catch (ObjectStorageException exception) {
            throw storageFailure(exception);
        }

        Instant now = clock.instant();
        Asset asset = new Asset();
        asset.setId(idGenerator.nextId());
        asset.setUserId(userId);
        asset.setConversationId(conversationId);
        asset.setKind(kind);
        asset.setStorageBackend(storage.backend());
        asset.setBucket(storageProperties.bucket());
        asset.setObjectKey(objectKey);
        asset.setMimeType(normalizedType);
        asset.setSizeBytes(stored.sizeBytes() > 0 ? stored.sizeBytes() : content.length);
        asset.setChecksum(stored.checksum());
        asset.setSource(AssetSource.GENERATION);
        asset.setGenerationTaskId(taskId);
        asset.setStatus(AssetStatus.READY);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetMapper.insert(asset);
        return asset;
    }

    public AssetDownloadTicket createDownloadUrl(UUID userId, UUID assetId) {
        Asset asset = requireAsset(userId, assetId);
        if (asset.getStatus() != AssetStatus.READY) {
            throw new BusinessException(AssetErrorCode.ASSET_NOT_READY);
        }
        Duration ttl = storageProperties.presignTtlOrDefault();
        try {
            PresignedUrl presigned = storage().presignGet(asset.getObjectKey(), ttl, null);
            return new AssetDownloadTicket(assetId, presigned.url(), presigned.expiresAt());
        } catch (ObjectStorageException exception) {
            throw storageFailure(exception);
        }
    }

    private String validate(CreateUploadCommand command) {
        if (command == null || command.kind() == null) {
            throw new BusinessException(AssetErrorCode.ASSET_KIND_REQUIRED);
        }
        if (command.filename() == null || command.filename().isBlank()) {
            throw new BusinessException(AssetErrorCode.ASSET_FILENAME_REQUIRED);
        }
        if (command.sizeBytes() <= 0) {
            throw new BusinessException(AssetErrorCode.ASSET_SIZE_INVALID);
        }
        if (command.sizeBytes() > storageProperties.maxUploadBytesOrDefault()) {
            throw new BusinessException(AssetErrorCode.ASSET_SIZE_TOO_LARGE);
        }
        String mimeType = AssetKind.normalizeMimeType(command.mimeType());
        if (!command.kind().supports(mimeType)) {
            throw new BusinessException(AssetErrorCode.ASSET_MIME_UNSUPPORTED);
        }
        return mimeType;
    }

    private UploadSession requireSession(
            UUID userId, UUID conversationId, UUID uploadSessionId) {
        UploadSession session =
                uploadSessionId == null ? null : uploadSessionMapper.selectById(uploadSessionId);
        if (session == null
                || !session.getUserId().equals(userId)
                || !session.getConversationId().equals(conversationId)) {
            throw new BusinessException(AssetErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return session;
    }

    private Asset requireAsset(UUID userId, UUID assetId) {
        Asset asset = assetId == null ? null : assetMapper.selectById(assetId);
        if (asset == null
                || !asset.getUserId().equals(userId)
                || asset.getDeletedAt() != null) {
            throw new BusinessException(AssetErrorCode.ASSET_NOT_FOUND);
        }
        return asset;
    }

    private void requireConversation(UUID userId, UUID conversationId) {
        conversationReferenceChecker.requireOwned(userId, conversationId);
    }

    private ObjectStorage storage() {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new SystemException(
                    AssetErrorCode.STORAGE_NOT_CONFIGURED,
                    "object storage is not configured",
                    null);
        }
        return storage;
    }

    private PresignedUrl presignPut(
            ObjectStorage storage, String objectKey, String mimeType) {
        try {
            return storage.presignPut(
                    objectKey, storageProperties.presignTtlOrDefault(), mimeType);
        } catch (ObjectStorageException exception) {
            throw storageFailure(exception);
        }
    }

    private ObjectMetadata head(String objectKey) {
        try {
            return storage().head(objectKey);
        } catch (ObjectStorageException exception) {
            if (exception.isNotFound()) {
                throw new BusinessException(AssetErrorCode.UPLOAD_NOT_COMPLETED);
            }
            throw storageFailure(exception);
        }
    }

    private StoredObject copy(String sourceKey, String targetKey, String mimeType) {
        try {
            return storage().copy(sourceKey, targetKey, mimeType);
        } catch (ObjectStorageException exception) {
            throw storageFailure(exception);
        }
    }

    private static String extensionFor(String mimeType) {
        return switch (AssetKind.normalizeMimeType(mimeType)) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            case "video/webm" -> "webm";
            case "application/pdf" -> "pdf";
            case "text/markdown" -> "md";
            case "text/plain" -> "txt";
            case "application/json" -> "json";
            case "application/zip" -> "zip";
            default -> "bin";
        };
    }

    private void fail(UploadSession session, Instant now) {
        session.setStatus(UploadSessionStatus.FAILED);
        session.setUpdatedAt(now);
        uploadSessionMapper.updateById(session);
    }

    private void expire(UploadSession session, Instant now) {
        session.setStatus(UploadSessionStatus.EXPIRED);
        session.setUpdatedAt(now);
        uploadSessionMapper.updateById(session);
    }

    private SystemException storageFailure(ObjectStorageException exception) {
        return new SystemException(
                AssetErrorCode.STORAGE_OPERATION_FAILED, exception.getMessage(), exception);
    }
}
