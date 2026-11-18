package app.pagefault.visionforge.agent.runtime;

public enum AgentRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
