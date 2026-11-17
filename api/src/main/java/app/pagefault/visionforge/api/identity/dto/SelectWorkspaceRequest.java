package app.pagefault.visionforge.api.identity.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SelectWorkspaceRequest(
        @NotBlank(message = "selectionToken 不能为空")
        String selectionToken,
        @NotNull(message = "workspaceId 不能为空")
        UUID workspaceId) {
}
