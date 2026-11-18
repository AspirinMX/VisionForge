package app.pagefault.visionforge.api.asset.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.asset.library.ConversationFileSource;
import app.pagefault.visionforge.asset.library.ConversationFileVersion;

/**
 * {@code assetId} is the binary behind an OBJECT_STORAGE version. It is resolved from the version
 * row first and, for legacy rows, from the generation or composition output that produced it.
 */
public record ConversationFileVersionResponse(
        UUID id,
        int version,
        String content,
        Long sizeBytes,
        String checksum,
        ConversationFileSource source,
        UUID assetId,
        Instant createdAt) {

    public static ConversationFileVersionResponse from(ConversationFileVersion version) {
        return from(version, version.getAssetId());
    }

    public static ConversationFileVersionResponse from(
            ConversationFileVersion version, UUID assetId) {
        return new ConversationFileVersionResponse(
                version.getId(),
                version.getVersion(),
                version.getContent(),
                version.getSizeBytes(),
                version.getChecksum(),
                version.getSource(),
                assetId,
                version.getCreatedAt());
    }
}
