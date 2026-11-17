package app.pagefault.visionforge.asset.library;

public enum WorkspaceItemKind {
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
