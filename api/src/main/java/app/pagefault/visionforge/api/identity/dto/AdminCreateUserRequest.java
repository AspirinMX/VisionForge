package app.pagefault.visionforge.api.identity.dto;

import app.pagefault.visionforge.identity.user.UserRole;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminCreateUserRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,

        @NotBlank(message = "密码不能为空")
        @Size(min = 12, max = 72, message = "密码长度必须在 12 到 72 之间")
        String password,

        @NotBlank(message = "显示名称不能为空")
        @Size(max = 100, message = "显示名称最多 100 个字符")
        String displayName,

        @NotNull(message = "角色不能为空")
        UserRole role) {
}
