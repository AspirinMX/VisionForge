package app.pagefault.visionforge.asset.storage;

import java.time.Instant;

public record ObjectMetadata(
        String objectKey,
        long sizeBytes,
        String contentType,
        String etag,
        String checksum,
        Instant lastModified) {
}
