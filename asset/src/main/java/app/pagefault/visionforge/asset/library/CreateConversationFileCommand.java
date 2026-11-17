package app.pagefault.visionforge.asset.library;

public record CreateConversationFileCommand(
        ConversationFileKind kind,
        String title,
        String path,
        String content,
        ConversationFileSource source) {
}
