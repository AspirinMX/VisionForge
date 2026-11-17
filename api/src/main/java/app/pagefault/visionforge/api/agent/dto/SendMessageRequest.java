package app.pagefault.visionforge.api.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 32000, message = "消息内容不能超过 32000 个字符")
        String content) {
}
