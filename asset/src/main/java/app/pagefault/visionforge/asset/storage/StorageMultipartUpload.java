package app.pagefault.visionforge.asset.storage;

public record StorageMultipartUpload(
        String bucket,
        String objectKey,
        String uploadId) {
}
