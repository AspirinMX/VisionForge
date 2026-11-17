package app.pagefault.visionforge.agent.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.pagefault.visionforge.agent.model.ReasoningEffort;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal OpenAI-compatible chat completions client. The provider boundary keeps the workflow
 * and tools independent of a specific SDK; a dedicated SDK adapter can replace this class later.
 */
@RequiredArgsConstructor
public class OpenAiCompatibleChatModelClient implements ChatModelClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);
    private static final int ERROR_BODY_LIMIT = 500;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Override
    public ChatCompletionResult complete(ChatCompletionRequest request) {
        long startedAt = System.nanoTime();
        try {
            String body = objectMapper.writeValueAsString(requestBody(request));
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint(request.baseUrl()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + request.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelProviderException(
                        "模型网关返回 HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            return parseResponse(response.body(), latencyMs);
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException("模型调用被中断", exception);
        } catch (Exception exception) {
            String detail = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            throw new ModelProviderException("模型调用失败: " + detail, exception);
        }
    }

    private Map<String, Object> requestBody(ChatCompletionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.modelId());
        body.put("messages", request.turns().stream()
                .map(turn -> Map.of("role", turn.role(), "content", turn.content()))
                .toList());
        body.put("max_tokens", request.maxOutputTokens());
        body.put("stream", false);
        ReasoningEffort effort = request.reasoningEffort();
        if (effort != null && effort != ReasoningEffort.NONE) {
            body.put("reasoning_effort", effort.name().toLowerCase(Locale.ROOT));
        }
        return body;
    }

    private ChatCompletionResult parseResponse(String raw, long latencyMs) {
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (RuntimeException exception) {
            throw new ModelProviderException("模型网关响应不是合法 JSON", exception);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ModelProviderException("模型网关响应缺少 choices");
        }
        JsonNode choice = choices.get(0);
        String content = extractContent(choice.path("message").path("content"));
        if (content == null || content.isBlank()) {
            throw new ModelProviderException("模型网关返回了空回复");
        }
        JsonNode usage = root.path("usage");
        return new ChatCompletionResult(
                content,
                intOrNull(usage.path("prompt_tokens")),
                intOrNull(usage.path("completion_tokens")),
                textOrNull(choice.path("finish_reason")),
                latencyMs);
    }

    private String extractContent(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder result = new StringBuilder();
            for (JsonNode part : content) {
                if (part.path("type").asText().equals("text")) {
                    result.append(part.path("text").asText());
                }
            }
            return result.toString();
        }
        return null;
    }

    private Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private URI endpoint(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(base + "/chat/completions");
    }

    private String truncate(String responseBody) {
        if (responseBody == null) {
            return "";
        }
        return responseBody.length() <= ERROR_BODY_LIMIT
                ? responseBody
                : responseBody.substring(0, ERROR_BODY_LIMIT);
    }
}
