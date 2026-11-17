package app.pagefault.visionforge.api.identity.dto;

import app.pagefault.visionforge.identity.user.UserRole;

import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(
        @NotNull(message = "角色不能为空")
        UserRole role) {
}
