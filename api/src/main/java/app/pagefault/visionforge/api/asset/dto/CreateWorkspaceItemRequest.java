package app.pagefault.visionforge.api.asset.dto;

import java.util.UUID;

import app.pagefault.visionforge.asset.library.ConversationItemRole;
import app.pagefault.visionforge.asset.library.WorkspaceItemKind;
import app.pagefault.visionforge.asset.library.WorkspaceItemVisibility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceItemRequest(
        @NotNull(message = "存储条目类型不能为空")
        WorkspaceItemKind kind,

        @NotBlank(message = "存储条目标题不能为空")
        @Size(max = 200, message = "存储条目标题不能超过 200 个字符")
        String title,

        @Size(max = 500, message = "存储条目路径不能超过 500 个字符")
        String path,

        @NotBlank(message = "存储条目内容不能为空")
        @Size(max = 1_048_576, message = "存储条目内容不能超过 1 MB")
        String content,

        WorkspaceItemVisibility visibility,

        UUID conversationId,

        ConversationItemRole role) {
}
