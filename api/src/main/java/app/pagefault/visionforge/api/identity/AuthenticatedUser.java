package app.pagefault.visionforge.api.identity;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;

final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
