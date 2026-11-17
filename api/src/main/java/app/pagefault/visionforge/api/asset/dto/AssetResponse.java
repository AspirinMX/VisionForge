package app.pagefault.visionforge.api.asset.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetKind;
import app.pagefault.visionforge.asset.media.AssetStatus;

public record AssetResponse(
        UUID id,
        UUID conversationId,
        AssetKind kind,
        String mimeType,
        long sizeBytes,
        String checksum,
        Integer width,
        Integer height,
        Long durationMs,
        AssetStatus status,
        Instant createdAt) {

    public static AssetResponse from(Asset asset) {
        return new AssetResponse(
                asset.getId(),
                asset.getConversationId(),
                asset.getKind(),
                asset.getMimeType(),
                asset.getSizeBytes(),
                asset.getChecksum(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getDurationMs(),
                asset.getStatus(),
                asset.getCreatedAt());
    }
}
