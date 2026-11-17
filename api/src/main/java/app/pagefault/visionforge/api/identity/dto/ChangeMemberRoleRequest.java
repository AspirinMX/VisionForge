package app.pagefault.visionforge.api.identity.dto;

import app.pagefault.visionforge.identity.workspace.WorkspaceRole;

import jakarta.validation.constraints.NotNull;

public record ChangeMemberRoleRequest(
        @NotNull(message = "角色不能为空")
        WorkspaceRole role) {
}
