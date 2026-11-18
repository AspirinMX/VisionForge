package app.pagefault.visionforge.api.composition.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record CreateCompositionTaskRequest(
        @NotNull(message = "会话 ID 不能为空")
        UUID conversationId,
        @NotNull(message = "分镜文件 ID 不能为空")
        UUID storyboardFileId,
        Integer storyboardVersion,
        JsonNode outputSpec) {
}
