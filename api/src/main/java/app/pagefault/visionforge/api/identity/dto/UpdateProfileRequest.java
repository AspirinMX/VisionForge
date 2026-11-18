package app.pagefault.visionforge.api.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "显示名称不能为空")
        @Size(max = 100, message = "显示名称最多 100 个字符")
        String displayName) {
}
