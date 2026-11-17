package app.pagefault.visionforge.api.identity.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RefreshRequest(
        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken,
        @NotNull(message = "workspaceId 不能为空")
        UUID workspaceId) {
}
