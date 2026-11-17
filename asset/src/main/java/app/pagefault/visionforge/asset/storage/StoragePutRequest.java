package app.pagefault.visionforge.asset.storage;

import java.io.InputStream;

/**
 * A server-side object write. The stream is consumed exactly once and must be closed by the
 * storage implementation or the caller after the call returns.
 */
public record StoragePutRequest(
        String objectKey,
        String contentType,
        long contentLength,
        InputStream content) {
}
