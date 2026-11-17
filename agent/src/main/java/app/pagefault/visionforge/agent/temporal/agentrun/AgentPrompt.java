package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.UUID;

/**
 * Prepared model input. It deliberately contains no API key or provider credentials because
 * activity inputs may be stored in Temporal workflow history. The actual Responses input is
 * rebuilt inside the activity from persisted messages, turns and tool results.
 */
public record AgentPrompt(
        UUID runId,
        UUID modelDefinitionId,
        int maxInputTokens) {
}
