package app.pagefault.visionforge.identity.auth;

import java.util.UUID;

/**
 * Internal token result. HTTP layers expose only the token fields.
 */
public record TokenPair(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UUID userId,
        String email) {
}
