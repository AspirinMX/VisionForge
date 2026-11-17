package app.pagefault.visionforge.api.agent.dto;

import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @Size(max = 200, message = "会话标题不能超过 200 个字符")
        String title) {
}
