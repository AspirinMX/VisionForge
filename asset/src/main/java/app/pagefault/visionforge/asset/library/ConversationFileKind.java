package app.pagefault.visionforge.asset.library;

public enum ConversationFileKind {
    SCRIPT,
    STORYBOARD,
    PROMPT,
    IMAGE,
    VIDEO,
    OTHER;

    public boolean isText() {
        return this == SCRIPT || this == STORYBOARD || this == PROMPT || this == OTHER;
    }
}
