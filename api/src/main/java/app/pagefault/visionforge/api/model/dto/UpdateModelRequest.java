package app.pagefault.visionforge.api.model.dto;

import app.pagefault.visionforge.agent.model.ExecutionMode;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.agent.model.ModelCapabilityType;
import app.pagefault.visionforge.agent.model.ReasoningEffort;
import app.pagefault.visionforge.agent.model.ReasoningReplayPolicy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateModelRequest(
        @Size(max = 100, message = "显示名称不能超过 100 个字符")
        String displayName,
        @Size(max = 500, message = "Base URL 不能超过 500 个字符")
        @Pattern(regexp = "^https?://.+", message = "Base URL 必须是 http 或 https 地址")
        String baseUrl,
        @Size(max = 1000, message = "API Key 不能超过 1000 个字符")
        String apiKey,
        @Size(max = 200, message = "模型 ID 不能超过 200 个字符")
        String modelId,
        ReasoningEffort reasoningEffort,
        ReasoningReplayPolicy reasoningReplay,
        @Min(value = 1, message = "最大输入 Token 必须大于 0")
        @Max(value = 2_000_000, message = "最大输入 Token 不能超过 2000000")
        Integer maxInputTokens,
        @Min(value = 1, message = "最大输出 Token 必须大于 0")
        @Max(value = 1_000_000, message = "最大输出 Token 不能超过 1000000")
        Integer maxOutputTokens,
        Boolean enabled,
        Boolean defaultModel,
        ModelCapabilityType capabilityType,
        MediaProtocol protocol,
        ExecutionMode executionMode,
        @Min(value = 0, message = "单任务价格不能为负数")
        Long priceUnits,
        Boolean supportsImageInput) {
}
