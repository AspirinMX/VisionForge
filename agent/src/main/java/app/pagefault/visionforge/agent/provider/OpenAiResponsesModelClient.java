package app.pagefault.visionforge.agent.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseUsage;

import tools.jackson.databind.JsonNode;

/**
 * Responses API client backed by the official OpenAI Java SDK.
 *
 * <p>The SDK owns the HTTP SSE transport, event decoding and response accumulation. This class only
 * maps domain requests to {@link ResponseCreateParams} and the accumulated {@link Response} back to
 * {@link ModelResponse}; it never builds HTTP requests or parses SSE frames itself.
 */
public class OpenAiResponsesModelClient implements ResponsesModelClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_CACHED_CLIENTS = 32;

    private final Map<String, OpenAIClient> clients = new ConcurrentHashMap<>();

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener) {
        long startedAt = System.nanoTime();
        try {
            ResponseCreateParams params = requestParams(request);
            ResponseAccumulator accumulator = ResponseAccumulator.create();
            try (StreamResponse<ResponseStreamEvent> stream =
                         client(request).responses().createStreaming(params)) {
                stream.stream().forEach(event -> {
                    accumulator.accumulate(event);
                    event.outputTextDelta().ifPresent(
                            delta -> listener.onTextDelta(delta.delta()));
                    event.reasoningTextDelta().ifPresent(
                            delta -> listener.onReasoningDelta(delta.delta()));
                    event.reasoningSummaryTextDelta().ifPresent(
                            delta -> listener.onReasoningSummaryDelta(delta.delta()));
                });
            }
            return toModelResponse(accumulator.response(), startedAt);
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ModelProviderException("模型调用失败: " + detail(exception), exception);
        }
    }

    private OpenAIClient client(ModelRequest request) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String key = baseUrl + '|' + request.apiKey();
        OpenAIClient cached = clients.get(key);
        if (cached != null) {
            return cached;
        }
        OpenAIClient created = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(request.apiKey())
                // The workflow never retries a paid model call; retrying here would double billing.
                .maxRetries(0)
                .timeout(REQUEST_TIMEOUT)
                .build();
        if (clients.size() >= MAX_CACHED_CLIENTS) {
            clients.clear();
        }
        OpenAIClient existing = clients.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }

    private ResponseCreateParams requestParams(ModelRequest request) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(request.modelId())
                .store(false)
                .maxOutputTokens((long) request.maxOutputTokens())
                .inputOfResponse(inputItems(request.input()))
                // Stateless replay needs the encrypted reasoning carrier back from the server.
                .include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT));

        if (request.instructions() != null && !request.instructions().isBlank()) {
            builder.instructions(request.instructions());
        }
        ReasoningEffort effort = sdkEffort(request.reasoningEffort());
        if (effort != null) {
            builder.reasoning(Reasoning.builder().effort(effort).build());
        }
        for (ModelToolSpec tool : request.tools()) {
            builder.addTool(FunctionTool.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(functionParameters(tool.parameters()))
                    .strict(false)
                    .build());
        }
        return builder.build();
    }

    private List<ResponseInputItem> inputItems(List<ModelInputItem> items) {
        List<ResponseInputItem> result = new ArrayList<>(items.size());
        for (ModelInputItem item : items) {
            switch (item) {
                case ModelInputItem.Message message -> result.add(
                        ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                                .role(messageRole(message.role()))
                                .content(message.content())
                                .build()));
                case ModelInputItem.FunctionCall call -> result.add(
                        ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                                .callId(call.callId())
                                .name(call.name())
                                .arguments(call.arguments())
                                .build()));
                case ModelInputItem.FunctionCallOutput output -> result.add(
                        ResponseInputItem.ofFunctionCallOutput(
                                ResponseInputItem.FunctionCallOutput.builder()
                                        .callId(output.callId())
                                        .output(output.output())
                                        .build()));
                case ModelInputItem.Reasoning reasoning -> result.add(
                        ResponseInputItem.ofReasoning(reasoningItem(reasoning)));
            }
        }
        return result;
    }

    /**
     * Rebuilds a reasoning input item. The server-side {@code id} is omitted on purpose: the
     * response was created with {@code store: false}, so an {@code rs_...} id would refer to
     * nothing and some gateways reject it.
     */
    private ResponseReasoningItem reasoningItem(ModelInputItem.Reasoning reasoning) {
        List<ResponseReasoningItem.Summary> summaries = reasoning.summary().stream()
                .filter(text -> text != null && !text.isBlank())
                .map(text -> ResponseReasoningItem.Summary.builder().text(text).build())
                .toList();
        return ResponseReasoningItem.builder()
                .id(JsonMissing.of())
                .summary(summaries)
                .encryptedContent(reasoning.encryptedContent())
                .build();
    }

    private EasyInputMessage.Role messageRole(String role) {
        return switch (role == null ? "" : role.toLowerCase(java.util.Locale.ROOT)) {
            case "assistant" -> EasyInputMessage.Role.ASSISTANT;
            case "system" -> EasyInputMessage.Role.SYSTEM;
            case "developer" -> EasyInputMessage.Role.DEVELOPER;
            default -> EasyInputMessage.Role.USER;
        };
    }

    private FunctionTool.Parameters functionParameters(JsonNode schema) {
        FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
        for (Map.Entry<String, JsonNode> property : schema.properties()) {
            Object value = plainValue(property.getValue());
            if (value != null) {
                builder.putAdditionalProperty(property.getKey(), JsonValue.from(value));
            }
        }
        return builder.build();
    }

    private Object plainValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                map.put(property.getKey(), plainValue(property.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode element : node) {
                list.add(plainValue(element));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return null;
    }

    private ReasoningEffort sdkEffort(app.pagefault.visionforge.agent.model.ReasoningEffort effort) {
        if (effort == null) {
            return null;
        }
        return switch (effort) {
            case NONE -> null;
            case MINIMAL -> ReasoningEffort.MINIMAL;
            case LOW -> ReasoningEffort.LOW;
            case MEDIUM -> ReasoningEffort.MEDIUM;
            case HIGH -> ReasoningEffort.HIGH;
        };
    }

    private ModelResponse toModelResponse(Response response, long startedAt) {
        String text = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(ResponseOutputText::text)
                .collect(Collectors.joining());

        List<ModelReasoning> reasoning = response.output().stream()
                .filter(ResponseOutputItem::isReasoning)
                .map(ResponseOutputItem::asReasoning)
                .map(item -> new ModelReasoning(
                        item.encryptedContent().orElse(null),
                        item.content()
                                .map(parts -> parts.stream()
                                        .map(ResponseReasoningItem.Content::text)
                                        .collect(Collectors.joining("\n")))
                                .orElse(""),
                        item.summary().stream()
                                .map(ResponseReasoningItem.Summary::text)
                                .toList()))
                .toList();

        List<ModelToolCall> toolCalls = response.output().stream()
                .filter(ResponseOutputItem::isFunctionCall)
                .map(ResponseOutputItem::asFunctionCall)
                .map(call -> new ModelToolCall(call.callId(), call.name(), call.arguments()))
                .toList();

        if (text.isBlank() && toolCalls.isEmpty()) {
            throw new ModelProviderException("模型返回了空回复");
        }

        ResponseUsage usage = response.usage().orElse(null);
        String finishReason = response.status()
                .map(ResponseStatus::asString)
                .orElse("completed");
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new ModelResponse(
                response.id(),
                text,
                reasoning,
                toolCalls,
                usage == null ? null : (int) usage.inputTokens(),
                usage == null ? null : (int) usage.outputTokens(),
                finishReason,
                latencyMs);
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ModelProviderException("模型 Base URL 未配置");
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String detail(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
