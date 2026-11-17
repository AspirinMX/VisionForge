package app.pagefault.visionforge.identity.workspace;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberView(
        UUID userId,
        String email,
        String displayName,
        WorkspaceRole role,
        Instant joinedAt) {
}
