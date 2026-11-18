package app.pagefault.visionforge.agent.conversation;

import java.util.List;
import java.util.UUID;

/**
 * A message together with its non-text parts. Asset references are the durable mapping between a
 * user message and the images it attached, so a reloaded conversation can show the same chips
 * without any client-side cache.
 */
public record MessageWithParts(
        Message message,
        List<UUID> assetIds,
        List<UUID> generationTaskIds) {
}
