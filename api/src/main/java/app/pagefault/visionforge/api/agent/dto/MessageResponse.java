package app.pagefault.visionforge.api.agent.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.conversation.Message;
import app.pagefault.visionforge.agent.conversation.MessageRole;
import app.pagefault.visionforge.agent.conversation.MessageStatus;
import app.pagefault.visionforge.agent.conversation.MessageWithParts;
import app.pagefault.visionforge.agent.runtime.AgentRunProcessView;

public record MessageResponse(
        UUID id,
        MessageRole role,
        String content,
        long sequenceNo,
        MessageStatus status,
        UUID senderUserId,
        Instant createdAt,
        List<UUID> assetIds,
        List<UUID> generationTaskIds,
        AgentRunProcessResponse process) {

    public static MessageResponse from(Message message) {
        return from(message, List.of(), List.of(), null);
    }

    public static MessageResponse from(MessageWithParts view) {
        return from(view.message(), view.assetIds(), view.generationTaskIds(), view.process());
    }

    private static MessageResponse from(
            Message message,
            List<UUID> assetIds,
            List<UUID> generationTaskIds,
            AgentRunProcessView process) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getSequenceNo(),
                message.getStatus(),
                message.getSenderUserId(),
                message.getCreatedAt(),
                assetIds,
                generationTaskIds,
                AgentRunProcessResponse.from(process));
    }
}
