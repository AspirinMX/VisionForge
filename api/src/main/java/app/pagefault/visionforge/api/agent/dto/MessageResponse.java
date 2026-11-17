package app.pagefault.visionforge.api.agent.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.agent.conversation.Message;
import app.pagefault.visionforge.agent.conversation.MessageRole;
import app.pagefault.visionforge.agent.conversation.MessageStatus;

public record MessageResponse(
        UUID id,
        MessageRole role,
        String content,
        long sequenceNo,
        MessageStatus status,
        UUID senderUserId,
        Instant createdAt) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getSequenceNo(),
                message.getStatus(),
                message.getSenderUserId(),
                message.getCreatedAt());
    }
}
