package app.pagefault.visionforge.api.agent.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.AgentRunStatus;

public record AgentRunResponse(
        UUID id,
        UUID conversationId,
        AgentRunStatus status,
        UUID assistantMessageId,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {

    public static AgentRunResponse from(AgentRun run) {
        return new AgentRunResponse(
                run.getId(),
                run.getConversationId(),
                run.getStatus(),
                run.getAssistantMessageId(),
                run.getErrorMessage(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt());
    }
}
