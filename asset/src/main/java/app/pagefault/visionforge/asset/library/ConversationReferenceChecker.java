package app.pagefault.visionforge.asset.library;

import java.util.UUID;

/**
 * Validates that a conversation exists and belongs to the requesting user before its work area is
 * touched.
 *
 * <p>Implemented by the agent module so the asset module does not depend on conversation entities.
 * Implementations must reject missing conversations and conversations owned by another user with
 * the same not-found semantics.
 */
public interface ConversationReferenceChecker {

    void requireOwned(UUID userId, UUID conversationId);
}
