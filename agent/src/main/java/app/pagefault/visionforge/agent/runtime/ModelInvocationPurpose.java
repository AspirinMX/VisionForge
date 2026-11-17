package app.pagefault.visionforge.agent.runtime;

public enum ModelInvocationPurpose {

    /** A conversational model call that produces the assistant reply. */
    CHAT,

    /** A local compaction call that produces a context summary. */
    COMPACT
}
