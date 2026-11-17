package app.pagefault.visionforge.asset.upload;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The result of creating an upload session. The client must PUT the bytes to {@code uploadUrl}
 * with the headers in {@code requiredHeaders}, then call complete.
 */
public record UploadTicket(
        UUID uploadSessionId,
        UUID assetId,
        URI uploadUrl,
        Instant uploadUrlExpiresAt,
        Instant sessionExpiresAt,
        Map<String, String> requiredHeaders) {
}
