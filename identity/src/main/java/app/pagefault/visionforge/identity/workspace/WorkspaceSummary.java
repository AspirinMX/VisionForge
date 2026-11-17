package app.pagefault.visionforge.identity.workspace;

import java.util.UUID;

/**
 * A workspace visible to a user together with the user's role in it. The role is null for a
 * platform administrator who is not a member of the workspace.
 */
public record WorkspaceSummary(
        UUID id,
        String name,
        boolean personal,
        WorkspaceRole role) {
}
