package app.pagefault.visionforge.identity.auth;

import java.util.UUID;

import app.pagefault.visionforge.identity.user.UserAccount;

/**
 * Result of a successful credential check: a token pair is issued immediately.
 */
public record LoginResult(
        TokenPair token,
        UUID userId,
        String email) {

    public static LoginResult of(TokenPair token, UserAccount user) {
        return new LoginResult(token, user.getId(), user.getEmail());
    }
}
