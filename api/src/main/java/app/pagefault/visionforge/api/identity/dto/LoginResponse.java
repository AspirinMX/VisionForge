package app.pagefault.visionforge.api.identity.dto;

import app.pagefault.visionforge.identity.auth.LoginResult;

public record LoginResponse(
        TokenResponse token,
        UserResponse user) {

    public static LoginResponse from(LoginResult result, UserResponse user) {
        return new LoginResponse(TokenResponse.from(result.token()), user);
    }
}
