package app.pagefault.visionforge.api.identity.dto;

import java.util.UUID;

import app.pagefault.visionforge.identity.workspace.WorkspaceRole;
import app.pagefault.visionforge.identity.workspace.WorkspaceSummary;

public record WorkspaceSummaryResponse(
        UUID id,
        String name,
        boolean personal,
        WorkspaceRole role) {

    public static WorkspaceSummaryResponse from(WorkspaceSummary summary) {
        return new WorkspaceSummaryResponse(
                summary.id(), summary.name(), summary.personal(), summary.role());
    }
}
