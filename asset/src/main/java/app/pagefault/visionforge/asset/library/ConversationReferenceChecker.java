package app.pagefault.visionforge.asset.library;

import java.util.UUID;

/**
 * Validates that a conversation exists in the target workspace before a library item is linked.
 *
 * <p>Implemented by the agent module so the asset module does not depend on conversation entities.
 */
public interface ConversationReferenceChecker {

    void require(UUID workspaceId, UUID conversationId);
}
