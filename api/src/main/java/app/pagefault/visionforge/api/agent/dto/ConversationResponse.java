package app.pagefault.visionforge.api.agent.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.agent.conversation.Conversation;

public record ConversationResponse(
        UUID id,
        String title,
        UUID modelDefinitionId,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getModelDefinitionId(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                conversation.getLastMessageAt());
    }
}
