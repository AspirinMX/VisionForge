package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.provider.ChatTurn;

/**
 * Prepared model input. It deliberately contains no API key or provider credentials because
 * activity inputs may be stored in Temporal workflow history.
 */
public record AgentPrompt(
        UUID runId,
        UUID modelDefinitionId,
        int maxInputTokens,
        List<ChatTurn> turns) {

    public AgentPrompt {
        turns = List.copyOf(turns);
    }
}
