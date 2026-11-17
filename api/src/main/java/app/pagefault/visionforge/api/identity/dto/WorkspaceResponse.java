package app.pagefault.visionforge.api.identity.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.identity.workspace.Workspace;
import app.pagefault.visionforge.identity.workspace.WorkspaceRole;
import app.pagefault.visionforge.identity.workspace.WorkspaceStatus;

public record WorkspaceResponse(
        UUID id,
        String name,
        String description,
        WorkspaceStatus status,
        boolean personal,
        WorkspaceRole role,
        Instant createdAt) {

    public static WorkspaceResponse from(Workspace workspace, WorkspaceRole role) {
        return new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getDescription(),
                workspace.getStatus(),
                workspace.isPersonal(),
                role,
                workspace.getCreatedAt());
    }
}
