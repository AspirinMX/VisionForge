package app.pagefault.visionforge.asset.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * Object storage port. Implementations may target any S3-compatible service (RustFS, MinIO, AWS
 * S3, ...) or a local backend; callers must not depend on a vendor SDK type.
 *
 * <p>Keys are always allocated by the server. Implementations must never accept a key supplied by
 * an end user or a model.
 */
public interface ObjectStorage {

    StorageBackend backend();

    StoredObject put(StoragePutRequest request);

    StoredObject copy(String sourceKey, String targetKey, String contentType);

    InputStream get(String objectKey, ByteRange range);

    ObjectMetadata head(String objectKey);

    void delete(String objectKey);

    PresignedUrl presignGet(String objectKey, Duration ttl, String downloadName);

    /**
     * Short-lived GET URL signed for the server-side/internal endpoint, used by worker processes
     * (for example FFmpeg) that must read an object without browser access.
     */
    PresignedUrl presignInternalGet(String objectKey, Duration ttl);

    PresignedUrl presignPut(String objectKey, Duration ttl, String contentType);

    StorageMultipartUpload initiateMultipart(String objectKey, String contentType);

    UploadedPart uploadPart(
            StorageMultipartUpload upload,
            int partNumber,
            InputStream content,
            long contentLength);

    StoredObject completeMultipart(StorageMultipartUpload upload, List<UploadedPart> parts);

    void abortMultipart(StorageMultipartUpload upload);
}
