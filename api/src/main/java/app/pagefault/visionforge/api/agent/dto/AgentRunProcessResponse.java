package app.pagefault.visionforge.api.agent.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.runtime.AgentRunProcessView;
import app.pagefault.visionforge.agent.runtime.AgentTurnProcessView;
import app.pagefault.visionforge.agent.runtime.ToolInvocationSummary;
import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;

public record AgentRunProcessResponse(
        UUID runId,
        Long durationMs,
        List<TurnProcessResponse> turns) {

    public static AgentRunProcessResponse from(AgentRunProcessView view) {
        if (view == null) {
            return null;
        }
        return new AgentRunProcessResponse(
                view.runId(),
                view.durationMs(),
                view.turns().stream().map(TurnProcessResponse::from).toList());
    }

    public record TurnProcessResponse(
            int turnNo,
            String reasoning,
            String stepText,
            List<ToolCallResponse> toolCalls) {

        public static TurnProcessResponse from(AgentTurnProcessView turn) {
            return new TurnProcessResponse(
                    turn.turnNo(),
                    turn.reasoning(),
                    turn.stepText(),
                    turn.toolCalls().stream().map(ToolCallResponse::from).toList());
        }
    }

    public record ToolCallResponse(
            String callId,
            String toolName,
            String action,
            String target,
            ToolInvocationStatus status,
            UUID fileId,
            UUID taskId,
            String errorMessage,
            Instant startedAt,
            Instant completedAt) {

        public static ToolCallResponse from(ToolInvocationSummary tool) {
            return new ToolCallResponse(
                    tool.callId(),
                    tool.toolName(),
                    tool.action(),
                    tool.target(),
                    tool.status(),
                    tool.fileId(),
                    tool.taskId(),
                    tool.errorMessage(),
                    tool.startedAt(),
                    tool.completedAt());
        }
    }
}
