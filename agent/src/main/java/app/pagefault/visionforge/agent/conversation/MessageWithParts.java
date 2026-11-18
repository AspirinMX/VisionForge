package app.pagefault.visionforge.agent.conversation;

import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.runtime.AgentRunProcessView;

/**
 * A message together with its non-text parts and optional AgentRun process timeline. Asset
 * references are the durable mapping between a user message and the images it attached, while the
 * process timeline lets reloaded assistant messages replay persisted reasoning and tool steps.
 */
public record MessageWithParts(
        Message message,
        List<UUID> assetIds,
        List<UUID> generationTaskIds,
        AgentRunProcessView process) {

    public MessageWithParts(
            Message message,
            List<UUID> assetIds,
            List<UUID> generationTaskIds) {
        this(message, assetIds, generationTaskIds, null);
    }
}
