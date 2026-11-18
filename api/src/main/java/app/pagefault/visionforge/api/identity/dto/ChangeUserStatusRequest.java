package app.pagefault.visionforge.api.identity.dto;

import app.pagefault.visionforge.identity.user.UserStatus;

import jakarta.validation.constraints.NotNull;

public record ChangeUserStatusRequest(
        @NotNull(message = "用户状态不能为空")
        UserStatus status) {
}
