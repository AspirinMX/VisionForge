package app.pagefault.visionforge.agent.model;

/**
 * How reasoning items are replayed into the next request.
 */
public enum ReasoningReplayPolicy {

    /** Replay only items that carry {@code encrypted_content}; ignore plaintext reasoning. */
    ENCRYPTED,

    /** Replay every reasoning item, including plaintext ones. */
    ALL,

    /** Never replay reasoning items. */
    NONE
}
