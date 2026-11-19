package app.pagefault.visionforge.api.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import app.pagefault.visionforge.agent.stream.AgentRunStreamPublisher;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bridges Redis pub/sub events from the worker into an HTTP SSE response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunStreamService {

    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DynamicRuntimeConfig> runtimeConfigProvider;
    private final Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();

    @Value("${visionforge.stream.timeout-ms:300000}")
    private long timeoutMs;

    public SseEmitter subscribe(UUID runId) {
        DynamicRuntimeConfig runtimeConfig = runtimeConfigProvider.getIfAvailable();
        long effectiveTimeout = runtimeConfig == null ? timeoutMs : runtimeConfig.streamTimeoutMs(timeoutMs);
        SseEmitter emitter = new SseEmitter(effectiveTimeout);
        ChannelTopic topic = new ChannelTopic(AgentRunStreamPublisher.channel(runId));
        MessageListener listener = (message, pattern) -> onMessage(runId, emitter, message);

        subscriptions.put(runId, new Subscription(topic, listener));
        listenerContainer.addMessageListener(listener, topic);
        emitter.onCompletion(() -> unsubscribe(runId));
        emitter.onTimeout(() -> {
            unsubscribe(runId);
            emitter.complete();
        });
        emitter.onError(exception -> unsubscribe(runId));

        try {
            emitter.send(SseEmitter.event()
                    .name("accepted")
                    .data(objectMapper.writeValueAsString(Map.of(
                            "type", "accepted",
                            "runId", runId.toString()))));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
            unsubscribe(runId);
        }
        return emitter;
    }

    public void unsubscribe(UUID runId) {
        Subscription subscription = subscriptions.remove(runId);
        if (subscription != null) {
            listenerContainer.removeMessageListener(
                    subscription.listener(), subscription.topic());
        }
    }

    private void onMessage(UUID runId, SseEmitter emitter, Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode event = objectMapper.readTree(payload);
            String type = event.path("type").asText("message");
            emitter.send(SseEmitter.event().name(type).data(payload));
            if ("done".equals(type) || "error".equals(type)) {
                emitter.complete();
                unsubscribe(runId);
            }
        } catch (Exception exception) {
            log.warn("Could not forward AgentRun stream event for {}", runId, exception);
            emitter.completeWithError(exception);
            unsubscribe(runId);
        }
    }

    private record Subscription(ChannelTopic topic, MessageListener listener) {
    }
}
