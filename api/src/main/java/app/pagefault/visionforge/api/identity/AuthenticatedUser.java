package app.pagefault.visionforge.api.identity;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;
import app.pagefault.visionforge.identity.workspace.WorkspaceActor;
import app.pagefault.visionforge.identity.workspace.WorkspaceRole;

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

    public static UUID workspaceId(Jwt jwt) {
        return parseUuid(jwt.getClaimAsString("wsid"));
    }

    public static WorkspaceRole workspaceRole(Jwt jwt) {
        String role = jwt.getClaimAsString("ws_role");
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            return WorkspaceRole.valueOf(role);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static boolean platformAdmin(Jwt jwt) {
        Object claim = jwt.getClaim("platform_admin");
        return Boolean.TRUE.equals(claim) || "ADMIN".equals(jwt.getClaimAsString("role"));
    }

    public static WorkspaceActor actor(Jwt jwt) {
        return new WorkspaceActor(id(jwt), platformAdmin(jwt));
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
