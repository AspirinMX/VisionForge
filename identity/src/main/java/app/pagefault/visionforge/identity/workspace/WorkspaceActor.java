package app.pagefault.visionforge.identity.workspace;

import java.util.UUID;

import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserRole;

/**
 * The authenticated actor for workspace operations.
 *
 * <p>Platform administrators bypass workspace membership checks by design; all other users are
 * authorized through their membership row.
 */
public record WorkspaceActor(UUID userId, boolean platformAdmin) {

    public static WorkspaceActor of(UserAccount user) {
        return new WorkspaceActor(user.getId(), user.getRole() == UserRole.ADMIN);
    }
}
