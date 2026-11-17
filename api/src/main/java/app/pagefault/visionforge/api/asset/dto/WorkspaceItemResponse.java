package app.pagefault.visionforge.api.asset.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.asset.library.WorkspaceItem;
import app.pagefault.visionforge.asset.library.WorkspaceItemKind;
import app.pagefault.visionforge.asset.library.WorkspaceItemStatus;
import app.pagefault.visionforge.asset.library.WorkspaceItemStorageKind;
import app.pagefault.visionforge.asset.library.WorkspaceItemVisibility;

public record WorkspaceItemResponse(
        UUID id,
        UUID workspaceId,
        WorkspaceItemKind kind,
        String title,
        String path,
        String mimeType,
        WorkspaceItemStorageKind storageKind,
        int currentVersion,
        WorkspaceItemStatus status,
        WorkspaceItemVisibility visibility,
        UUID createdBy,
        UUID originConversationId,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkspaceItemResponse from(WorkspaceItem item) {
        return new WorkspaceItemResponse(
                item.getId(),
                item.getWorkspaceId(),
                item.getKind(),
                item.getTitle(),
                item.getPath(),
                item.getMimeType(),
                item.getStorageKind(),
                item.getCurrentVersion(),
                item.getStatus(),
                item.getVisibility(),
                item.getCreatedBy(),
                item.getOriginConversationId(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
