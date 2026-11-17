package app.pagefault.visionforge.api.identity.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record SwitchWorkspaceRequest(
        @NotNull(message = "workspaceId 不能为空")
        UUID workspaceId) {
}
