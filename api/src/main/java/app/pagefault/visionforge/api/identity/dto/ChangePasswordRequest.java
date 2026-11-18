package app.pagefault.visionforge.api.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "当前密码不能为空")
        String currentPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 12, max = 72, message = "密码长度必须在 12 到 72 之间")
        String newPassword) {
}
