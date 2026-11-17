package app.pagefault.visionforge.identity.auth;

import java.util.UUID;

import app.pagefault.visionforge.identity.workspace.WorkspaceRole;

/**
 * A newly issued workspace-scoped access token. Switching workspaces does not rotate the refresh
 * token, so no refresh token is returned here.
 */
public record AccessToken(
        String accessToken,
        String tokenType,
        long expiresIn,
        UUID userId,
        String email,
        UUID workspaceId,
        WorkspaceRole workspaceRole) {
}
