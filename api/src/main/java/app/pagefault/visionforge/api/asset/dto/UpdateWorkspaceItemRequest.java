package app.pagefault.visionforge.api.asset.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateWorkspaceItemRequest(
        @NotNull(message = "必须提供 baseVersion")
        @Min(value = 1, message = "baseVersion 必须大于 0")
        Integer baseVersion,

        @NotBlank(message = "存储条目内容不能为空")
        @Size(max = 1_048_576, message = "存储条目内容不能超过 1 MB")
        String content,

        @Size(max = 200, message = "存储条目标题不能超过 200 个字符")
        String title) {
}
