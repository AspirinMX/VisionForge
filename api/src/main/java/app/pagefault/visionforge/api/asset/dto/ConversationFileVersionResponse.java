package app.pagefault.visionforge.api.asset.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.asset.library.ConversationFileSource;
import app.pagefault.visionforge.asset.library.ConversationFileVersion;

public record ConversationFileVersionResponse(
        UUID id,
        int version,
        String content,
        Long sizeBytes,
        String checksum,
        ConversationFileSource source,
        Instant createdAt) {

    public static ConversationFileVersionResponse from(ConversationFileVersion version) {
        return new ConversationFileVersionResponse(
                version.getId(),
                version.getVersion(),
                version.getContent(),
                version.getSizeBytes(),
                version.getChecksum(),
                version.getSource(),
                version.getCreatedAt());
    }
}
