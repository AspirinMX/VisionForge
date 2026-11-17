package app.pagefault.visionforge.api.identity.dto;

import java.util.UUID;

import app.pagefault.visionforge.identity.auth.TokenPair;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UUID userId,
        String email) {

    public static TokenResponse from(TokenPair pair) {
        return new TokenResponse(
                pair.accessToken(),
                pair.refreshToken(),
                pair.tokenType(),
                pair.expiresIn(),
                pair.userId(),
                pair.email());
    }
}
