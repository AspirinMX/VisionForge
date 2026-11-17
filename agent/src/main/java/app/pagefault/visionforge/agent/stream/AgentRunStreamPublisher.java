package app.pagefault.visionforge.agent.stream;

import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes text deltas over Redis pub/sub. Streaming is best-effort: a Redis outage must not
 * fail the model execution or lose the persisted assistant message.
 */
@Slf4j
@RequiredArgsConstructor
public class AgentRunStreamPublisher {

    public static final String CHANNEL_PREFIX = "visionforge:agent-run:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public static String channel(UUID runId) {
        return CHANNEL_PREFIX + runId;
    }

    public void publishDelta(UUID runId, String content) {
        publish(runId, AgentRunStreamEvent.delta(content));
    }

    public void publishReasoning(UUID runId, String content) {
        publish(runId, AgentRunStreamEvent.reasoning(content));
    }

    public void publishReasoningSummary(UUID runId, String content) {
        publish(runId, AgentRunStreamEvent.reasoningSummary(content));
    }

    public void publishDone(UUID runId, UUID assistantMessageId) {
        publish(runId, AgentRunStreamEvent.done(assistantMessageId));
    }

    public void publishError(UUID runId, String message) {
        publish(runId, AgentRunStreamEvent.error(message));
    }

    private void publish(UUID runId, AgentRunStreamEvent event) {
        try {
            redisTemplate.convertAndSend(
                    channel(runId), objectMapper.writeValueAsString(event));
        } catch (RuntimeException exception) {
            log.warn("Could not publish stream event for AgentRun {}", runId, exception);
        }
    }
}
