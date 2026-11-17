package app.pagefault.visionforge.identity.auth;

import java.util.UUID;

/**
 * A newly issued access token. The token is scoped to the user only; there is no workspace
 * dimension in the token.
 */
public record AccessToken(
        String accessToken,
        String tokenType,
        long expiresIn,
        UUID userId,
        String email) {
}
