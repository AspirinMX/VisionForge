package app.pagefault.visionforge.api.asset.dto;

import app.pagefault.visionforge.asset.library.ConversationFileKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateConversationFileRequest(
        @NotNull(message = "文件类型不能为空")
        ConversationFileKind kind,
        @NotBlank(message = "文件标题不能为空")
        @Size(max = 200, message = "文件标题不能超过 200 个字符")
        String title,
        @Size(max = 500, message = "文件路径不能超过 500 个字符")
        String path,
        @NotBlank(message = "文件内容不能为空")
        String content) {
}
