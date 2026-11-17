package app.pagefault.visionforge.api.identity.dto;

import app.pagefault.visionforge.identity.auth.TokenPair;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn) {

    public static TokenResponse from(TokenPair pair) {
        return new TokenResponse(
                pair.accessToken(),
                pair.refreshToken(),
                pair.tokenType(),
                pair.expiresIn());
    }
}
