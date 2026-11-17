package app.pagefault.visionforge.agent.generation;

public enum GenerationStatus {
    PENDING,
    SUBMITTING,
    RUNNING,
    ARCHIVING,
    PROCESSING,
    SUCCEEDED,
    PARTIAL_SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN,
    ARCHIVE_FAILED,
    DELIVERY_FAILED;

    public boolean terminal() {
        return this == SUCCEEDED
                || this == PARTIAL_SUCCEEDED
                || this == FAILED
                || this == CANCELLED
                || this == UNKNOWN
                || this == DELIVERY_FAILED;
    }
}
