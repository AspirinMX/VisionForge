package app.pagefault.visionforge.agent.provider;

/**
 * A function call requested by the model. {@code arguments} stays a raw JSON string because the
 * model may emit invalid JSON that the tool layer has to reject explicitly.
 */
public record ModelToolCall(String callId, String name, String arguments) {
}
