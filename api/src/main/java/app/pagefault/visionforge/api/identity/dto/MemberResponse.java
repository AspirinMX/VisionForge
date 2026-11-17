package app.pagefault.visionforge.api.identity.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.identity.workspace.WorkspaceMemberView;
import app.pagefault.visionforge.identity.workspace.WorkspaceRole;

public record MemberResponse(
        UUID userId,
        String email,
        String displayName,
        WorkspaceRole role,
        Instant joinedAt) {

    public static MemberResponse from(WorkspaceMemberView view) {
        return new MemberResponse(
                view.userId(), view.email(), view.displayName(), view.role(), view.joinedAt());
    }
}
