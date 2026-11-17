package app.pagefault.visionforge.api.asset.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateConversationFileRequest(
        @NotNull(message = "baseVersion 不能为空")
        @Min(value = 1, message = "baseVersion 必须大于 0")
        Integer baseVersion,
        @NotBlank(message = "文件内容不能为空")
        String content,
        @Size(max = 200, message = "文件标题不能超过 200 个字符")
        String title) {
}
