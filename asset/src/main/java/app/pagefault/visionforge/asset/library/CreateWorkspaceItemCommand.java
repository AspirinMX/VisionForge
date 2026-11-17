package app.pagefault.visionforge.asset.library;

import java.util.UUID;

public record CreateWorkspaceItemCommand(
        WorkspaceItemKind kind,
        String title,
        String path,
        String content,
        WorkspaceItemVisibility visibility,
        UUID conversationId,
        ConversationItemRole role,
        WorkspaceItemSource source) {
}
