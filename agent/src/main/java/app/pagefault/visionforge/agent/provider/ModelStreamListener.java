package app.pagefault.visionforge.agent.provider;

/**
 * Receives streamed model increments. Text and reasoning are delivered separately so callers can
 * label them differently downstream.
 */
public interface ModelStreamListener {

    ModelStreamListener NOOP = new ModelStreamListener() {
    };

    default void onTextDelta(String delta) {
    }

    /** Plaintext chain of thought returned by providers that expose it. */
    default void onReasoningDelta(String delta) {
    }

    /** Readable summary of the reasoning that an encrypted reasoning model chooses to expose. */
    default void onReasoningSummaryDelta(String delta) {
    }
}
