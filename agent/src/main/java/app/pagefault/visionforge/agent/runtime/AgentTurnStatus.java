package app.pagefault.visionforge.agent.runtime;

public enum AgentTurnStatus {

    /** The model finished the turn and its output was persisted normally. */
    COMPLETED,

    /** The upstream response stopped early; the turn is kept for diagnosis. */
    INCOMPLETE
}
