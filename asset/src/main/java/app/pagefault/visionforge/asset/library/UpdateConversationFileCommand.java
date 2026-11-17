package app.pagefault.visionforge.asset.library;

public record UpdateConversationFileCommand(
        Integer baseVersion,
        String content,
        String title,
        ConversationFileSource source) {
}
