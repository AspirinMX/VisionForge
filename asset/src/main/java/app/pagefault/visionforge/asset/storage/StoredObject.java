package app.pagefault.visionforge.asset.storage;

/**
 * Result of a completed object write. {@code checksum} is the lowercase hex SHA-256 digest when the
 * backend reports one.
 */
public record StoredObject(
        String objectKey,
        long sizeBytes,
        String etag,
        String checksum) {
}
