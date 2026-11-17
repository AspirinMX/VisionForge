package app.pagefault.visionforge.asset.media;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record AssetDownloadTicket(
        UUID assetId,
        URI downloadUrl,
        Instant expiresAt) {
}
