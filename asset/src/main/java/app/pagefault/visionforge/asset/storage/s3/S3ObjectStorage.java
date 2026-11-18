package app.pagefault.visionforge.asset.storage.s3;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import app.pagefault.visionforge.asset.storage.ByteRange;
import app.pagefault.visionforge.asset.storage.ObjectMetadata;
import app.pagefault.visionforge.asset.storage.ObjectStorage;
import app.pagefault.visionforge.asset.storage.ObjectStorageException;
import app.pagefault.visionforge.asset.storage.PresignedUrl;
import app.pagefault.visionforge.asset.storage.StorageBackend;
import app.pagefault.visionforge.asset.storage.StorageMultipartUpload;
import app.pagefault.visionforge.asset.storage.StoragePutRequest;
import app.pagefault.visionforge.asset.storage.StoredObject;
import app.pagefault.visionforge.asset.storage.UploadedPart;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 API adapter. It deliberately uses only the S3 subset that RustFS documents as tested:
 * object CRUD, presigned GET/PUT, range reads and multipart upload.
 */
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final S3Presigner internalPresigner;
    private final String bucket;
    private final Clock clock;

    public S3ObjectStorage(
            S3Client client,
            S3Presigner presigner,
            S3Presigner internalPresigner,
            String bucket,
            Clock clock) {
        this.client = client;
        this.presigner = presigner;
        this.internalPresigner = internalPresigner;
        this.bucket = bucket;
        this.clock = clock;
    }

    @Override
    public StorageBackend backend() {
        return StorageBackend.S3;
    }

    @Override
    public StoredObject put(StoragePutRequest request) {
        return execute(() -> {
            PutObjectRequest awsRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(request.objectKey())
                    .contentType(request.contentType())
                    .contentLength(request.contentLength())
                    .build();
            PutObjectResponse response = client.putObject(
                    awsRequest,
                    RequestBody.fromInputStream(request.content(), request.contentLength()));
            return new StoredObject(
                    request.objectKey(),
                    request.contentLength(),
                    response.eTag(),
                    base64ToHex(response.checksumSHA256()));
        });
    }

    @Override
    public StoredObject copy(String sourceKey, String targetKey, String contentType) {
        return execute(() -> {
            CopyObjectResponse response = client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(sourceKey)
                    .destinationBucket(bucket)
                    .destinationKey(targetKey)
                    .contentType(contentType)
                    .build());
            ObjectMetadata metadata = headWithoutWrapping(targetKey);
            String etag = response.copyObjectResult() == null
                    ? metadata.etag()
                    : response.copyObjectResult().eTag();
            return new StoredObject(
                    targetKey, metadata.sizeBytes(), etag, metadata.checksum());
        });
    }

    @Override
    public InputStream get(String objectKey, ByteRange range) {
        return execute(() -> {
            GetObjectRequest.Builder builder =
                    GetObjectRequest.builder().bucket(bucket).key(objectKey);
            if (range != null) {
                builder.range("bytes=" + range.start() + "-"
                        + (range.end() == null ? "" : range.end()));
            }
            ResponseInputStream<GetObjectResponse> stream =
                    client.getObject(builder.build());
            return stream;
        });
    }

    @Override
    public ObjectMetadata head(String objectKey) {
        return execute(() -> {
            HeadObjectResponse response = client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return new ObjectMetadata(
                    objectKey,
                    response.contentLength(),
                    response.contentType(),
                    response.eTag(),
                    base64ToHex(response.checksumSHA256()),
                    response.lastModified());
        });
    }

    @Override
    public void delete(String objectKey) {
        execute(() -> {
            client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return null;
        });
    }

    @Override
    public PresignedUrl presignGet(String objectKey, Duration ttl, String downloadName) {
        return execute(() -> {
            GetObjectRequest.Builder builder =
                    GetObjectRequest.builder().bucket(bucket).key(objectKey);
            if (downloadName != null && !downloadName.isBlank()) {
                builder.responseContentDisposition(
                        "attachment; filename=\"" + downloadName.replace("\"", "") + "\"");
            }
            PresignedGetObjectRequest presigned = presigner.presignGetObject(
                    GetObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .getObjectRequest(builder.build())
                            .build());
            return new PresignedUrl(
                    URI.create(presigned.url().toString()), clock.instant().plus(ttl), Map.of());
        });
    }

    @Override
    public PresignedUrl presignInternalGet(String objectKey, Duration ttl) {
        return execute(() -> {
            PresignedGetObjectRequest presigned = internalPresigner.presignGetObject(
                    GetObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(objectKey)
                                    .build())
                            .build());
            return new PresignedUrl(
                    URI.create(presigned.url().toString()),
                    clock.instant().plus(ttl),
                    Map.of());
        });
    }

    @Override
    public PresignedUrl presignPut(String objectKey, Duration ttl, String contentType) {
        return execute(() -> {
            PutObjectRequest awsRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .build();
            PresignedPutObjectRequest presigned = presigner.presignPutObject(
                    PutObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .putObjectRequest(awsRequest)
                            .build());
            return new PresignedUrl(
                    URI.create(presigned.url().toString()),
                    clock.instant().plus(ttl),
                    contentType == null || contentType.isBlank()
                            ? Map.of()
                            : Map.of("Content-Type", contentType));
        });
    }

    @Override
    public StorageMultipartUpload initiateMultipart(String objectKey, String contentType) {
        return execute(() -> {
            CreateMultipartUploadRequest.Builder builder = CreateMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(objectKey);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            CreateMultipartUploadResponse response =
                    client.createMultipartUpload(builder.build());
            return new StorageMultipartUpload(bucket, objectKey, response.uploadId());
        });
    }

    @Override
    public UploadedPart uploadPart(
            StorageMultipartUpload upload,
            int partNumber,
            InputStream content,
            long contentLength) {
        return execute(() -> {
            var response = client.uploadPart(
                    UploadPartRequest.builder()
                            .bucket(upload.bucket())
                            .key(upload.objectKey())
                            .uploadId(upload.uploadId())
                            .partNumber(partNumber)
                            .contentLength(contentLength)
                            .build(),
                    RequestBody.fromInputStream(content, contentLength));
            return new UploadedPart(partNumber, response.eTag());
        });
    }

    @Override
    public StoredObject completeMultipart(
            StorageMultipartUpload upload, List<UploadedPart> parts) {
        return execute(() -> {
            List<CompletedPart> completedParts = parts.stream()
                    .map(part -> CompletedPart.builder()
                            .partNumber(part.partNumber())
                            .eTag(part.etag())
                            .build())
                    .toList();
            CompleteMultipartUploadResponse response = client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(upload.bucket())
                            .key(upload.objectKey())
                            .uploadId(upload.uploadId())
                            .multipartUpload(CompletedMultipartUpload.builder()
                                    .parts(completedParts)
                                    .build())
                            .build());
            ObjectMetadata metadata = headWithoutWrapping(upload.objectKey());
            return new StoredObject(
                    upload.objectKey(),
                    metadata.sizeBytes(),
                    response.eTag(),
                    metadata.checksum());
        });
    }

    @Override
    public void abortMultipart(StorageMultipartUpload upload) {
        execute(() -> {
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(upload.bucket())
                    .key(upload.objectKey())
                    .uploadId(upload.uploadId())
                    .build());
            return null;
        });
    }

    private ObjectMetadata headWithoutWrapping(String objectKey) {
        HeadObjectResponse response = client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
        return new ObjectMetadata(
                objectKey,
                response.contentLength(),
                response.contentType(),
                response.eTag(),
                base64ToHex(response.checksumSHA256()),
                response.lastModified());
    }

    private <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (S3Exception exception) {
            String detail = exception.awsErrorDetails() == null
                    ? exception.getMessage()
                    : exception.awsErrorDetails().errorMessage();
            throw new ObjectStorageException(
                    exception.statusCode() == 404,
                    "S3 " + exception.statusCode() + ": " + detail,
                    exception);
        }
    }

    private static String base64ToHex(String checksum) {
        if (checksum == null || checksum.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(Base64.getDecoder().decode(checksum));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
