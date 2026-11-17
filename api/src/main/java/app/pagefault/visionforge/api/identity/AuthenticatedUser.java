package app.pagefault.visionforge.api.identity;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;

public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    public static String role(Jwt jwt) {
        return jwt.getClaimAsString("role");
    }

    public static boolean admin(Jwt jwt) {
        return "ADMIN".equals(role(jwt));
    }
}
