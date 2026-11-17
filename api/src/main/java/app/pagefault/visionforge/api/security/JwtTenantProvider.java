package app.pagefault.visionforge.api.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.common.audit.TenantProvider;

/**
 * Resolves the current workspace from the access token. The value is server-controlled and never
 * comes from request parameters or model output.
 */
@Component
public class JwtTenantProvider implements TenantProvider {

    @Override
    public UUID currentWorkspaceId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return AuthenticatedUser.workspaceId(jwt);
        }
        return null;
    }
}
