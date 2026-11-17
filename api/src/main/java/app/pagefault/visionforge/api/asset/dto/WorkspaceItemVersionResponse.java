package app.pagefault.visionforge.api.asset.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.asset.library.WorkspaceItemSource;
import app.pagefault.visionforge.asset.library.WorkspaceItemVersion;

public record WorkspaceItemVersionResponse(
        UUID id,
        UUID itemId,
        int version,
        String content,
        Long sizeBytes,
        String checksum,
        WorkspaceItemSource source,
        UUID createdBy,
        Instant createdAt) {

    public static WorkspaceItemVersionResponse from(
            WorkspaceItemVersion version, boolean includeContent) {
        return new WorkspaceItemVersionResponse(
                version.getId(),
                version.getItemId(),
                version.getVersion(),
                includeContent ? version.getContent() : null,
                version.getSizeBytes(),
                version.getChecksum(),
                version.getSource(),
                version.getCreatedBy(),
                version.getCreatedAt());
    }
}
