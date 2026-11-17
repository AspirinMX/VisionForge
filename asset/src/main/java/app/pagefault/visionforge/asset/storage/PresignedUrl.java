package app.pagefault.visionforge.asset.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * A short-lived URL plus the headers the caller must send unchanged. Presigned URLs are a delivery
 * mechanism only; the permanent identity of an object remains its bucket and key.
 */
public record PresignedUrl(
        URI url,
        Instant expiresAt,
        Map<String, String> requiredHeaders) {
}
