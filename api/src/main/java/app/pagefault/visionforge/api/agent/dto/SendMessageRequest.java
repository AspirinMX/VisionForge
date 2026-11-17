package app.pagefault.visionforge.api.agent.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A user turn. {@code assetIds} reference already-uploaded images of the same conversation; they
 * are passed to the main model as input_image content blocks for this turn only.
 */
public record SendMessageRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 32000, message = "消息内容不能超过 32000 个字符")
        String content,
        @Size(max = 8, message = "单条消息最多附带 8 个素材")
        List<UUID> assetIds) {
}
