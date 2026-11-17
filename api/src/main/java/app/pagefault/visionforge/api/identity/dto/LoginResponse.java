package app.pagefault.visionforge.api.identity.dto;

import java.util.List;

import app.pagefault.visionforge.identity.auth.LoginResult;

public record LoginResponse(
        boolean requiresWorkspaceSelection,
        String selectionToken,
        TokenResponse token,
        List<WorkspaceSummaryResponse> workspaces) {

    public LoginResponse {
        workspaces = List.copyOf(workspaces);
    }

    public static LoginResponse from(LoginResult result) {
        return new LoginResponse(
                result.requiresWorkspaceSelection(),
                result.selectionToken(),
                result.token() == null ? null : TokenResponse.from(result.token()),
                result.workspaces().stream().map(WorkspaceSummaryResponse::from).toList());
    }
}
