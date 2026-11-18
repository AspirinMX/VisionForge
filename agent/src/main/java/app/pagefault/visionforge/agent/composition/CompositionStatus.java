package app.pagefault.visionforge.agent.composition;

public enum CompositionStatus {
    PENDING,
    VALIDATING,
    COMPOSING,
    UPLOADING,
    PUBLISHING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
