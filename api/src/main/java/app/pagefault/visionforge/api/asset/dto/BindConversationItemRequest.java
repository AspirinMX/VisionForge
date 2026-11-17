package app.pagefault.visionforge.api.asset.dto;

import java.util.UUID;

import app.pagefault.visionforge.asset.library.ConversationItemRole;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record BindConversationItemRequest(
        @NotNull(message = "itemId 不能为空")
        UUID itemId,

        ConversationItemRole role,

        @Min(value = 1, message = "pinnedVersion 必须大于 0")
        Integer pinnedVersion) {
}
