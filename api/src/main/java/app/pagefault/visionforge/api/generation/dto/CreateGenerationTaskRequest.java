package app.pagefault.visionforge.api.generation.dto;

import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.generation.GenerationType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Api-level generation request. Identity, model capability and input ownership are always
 * validated server-side; the body never carries a user id, object key or vendor task id.
 */
public record CreateGenerationTaskRequest(
        @NotNull(message = "会话 ID 不能为空")
        UUID conversationId,
        @NotNull(message = "生成类型不能为空")
        GenerationType type,
        UUID modelDefinitionId,
        @NotBlank(message = "提示词不能为空")
        @Size(max = 32000, message = "提示词不能超过 32000 个字符")
        String prompt,
        JsonNode parameters,
        @Size(max = 8, message = "单个任务最多引用 8 个输入素材")
        List<@Valid GenerationInputRequest> inputs,
        UUID sourceTaskId) {
}
