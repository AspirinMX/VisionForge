package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.UUID;

/**
 * Result of one tool dispatch. A waiting result means the tool created a durable task and the
 * workflow must wait for its terminal state before continuing the model loop.
 */
public record ToolExecution(boolean waiting, UUID taskId) {

    public static ToolExecution completed() {
        return new ToolExecution(false, null);
    }

    public static ToolExecution waiting(UUID taskId) {
        return new ToolExecution(true, taskId);
    }
}
