package app.pagefault.visionforge.identity.auth;

import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.workspace.WorkspaceSummary;

/**
 * Result of a successful credential check. A user with a single workspace receives a token pair
 * immediately; a user with several workspaces receives a short-lived selection token.
 */
public record LoginResult(
        boolean requiresWorkspaceSelection,
        String selectionToken,
        TokenPair token,
        List<WorkspaceSummary> workspaces,
        UUID userId,
        String email) {

    public LoginResult {
        workspaces = List.copyOf(workspaces);
    }

    public static LoginResult selected(
            TokenPair token, List<WorkspaceSummary> workspaces, UserAccount user) {
        return new LoginResult(
                false, null, token, workspaces, user.getId(), user.getEmail());
    }

    public static LoginResult selectionRequired(
            String selectionToken, List<WorkspaceSummary> workspaces, UserAccount user) {
        return new LoginResult(
                true, selectionToken, null, workspaces, user.getId(), user.getEmail());
    }
}
