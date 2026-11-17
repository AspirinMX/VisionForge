package app.pagefault.visionforge.api.identity.dto;

import java.util.UUID;

import app.pagefault.visionforge.identity.auth.AccessToken;
import app.pagefault.visionforge.identity.workspace.WorkspaceRole;

public record AccessTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UUID workspaceId,
        WorkspaceRole workspaceRole) {

    public static AccessTokenResponse from(AccessToken token) {
        return new AccessTokenResponse(
                token.accessToken(),
                token.tokenType(),
                token.expiresIn(),
                token.workspaceId(),
                token.workspaceRole());
    }
}
