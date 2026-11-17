package app.pagefault.visionforge.api.asset.dto;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.asset.media.AssetDownloadTicket;

public record AssetDownloadUrlResponse(
        UUID assetId,
        URI downloadUrl,
        Instant expiresAt) {

    public static AssetDownloadUrlResponse from(AssetDownloadTicket ticket) {
        return new AssetDownloadUrlResponse(
                ticket.assetId(), ticket.downloadUrl(), ticket.expiresAt());
    }
}
