package app.pagefault.visionforge.asset.library;

public record UpdateWorkspaceItemCommand(
        Integer baseVersion,
        String content,
        String title,
        WorkspaceItemSource source) {
}
