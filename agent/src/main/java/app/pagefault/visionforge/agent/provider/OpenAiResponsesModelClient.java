package app.pagefault.visionforge.agent.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
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
    private final String userAgent;

    public OpenAiResponsesModelClient() {
        this("");
    }

    public OpenAiResponsesModelClient(String userAgent) {
        this.userAgent = userAgent == null ? "" : userAgent.trim();
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamListener listener) {
        long startedAt = System.nanoTime();
        try {
            ResponseCreateParams params = requestParams(request);
            ResponseAccumulator accumulator = ResponseAccumulator.create();
            SummaryStreamTracker summaryTracker = new SummaryStreamTracker(listener);
            try (StreamResponse<ResponseStreamEvent> stream =
                         client(request).responses().createStreaming(params)) {
                stream.stream().forEach(event -> {
                    accumulator.accumulate(event);
                    event.outputTextDelta().ifPresent(
                            delta -> listener.onTextDelta(delta.delta()));
                    event.reasoningTextDelta().ifPresent(
                            delta -> listener.onReasoningDelta(delta.delta()));
                    summaryTracker.accept(event);
                });
            }
            return toModelResponse(accumulator.response(), summaryTracker, startedAt);
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
        var builder = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(request.apiKey())
                // The workflow never retries a paid model call; retrying here would double billing.
                .maxRetries(0)
                .timeout(REQUEST_TIMEOUT);
        if (!userAgent.isBlank()) {
            builder.putHeader("User-Agent", userAgent);
        }
        OpenAIClient created = builder.build();
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
            builder.reasoning(Reasoning.builder()
                    .effort(effort)
                    .summary(Reasoning.Summary.AUTO)
                    .build());
        }
        for (ModelToolSpec tool : request.tools()) {
            builder.addTool(FunctionTool.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(functionParameters(tool.parameters()))
                    .strict(false)
                    .build());
        }
        String cacheKey = request.promptCacheKey();
        if (cacheKey != null && !cacheKey.isBlank()) {
            builder.promptCacheKey(cacheKey);
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
                case ModelInputItem.UserMessage message -> result.add(
                        ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                                .role(EasyInputMessage.Role.USER)
                                .contentOfResponseInputMessageContentList(
                                        userMessageContent(message))
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

    private List<ResponseInputContent> userMessageContent(ModelInputItem.UserMessage message) {
        List<ResponseInputContent> content = new ArrayList<>(message.images().size() + 1);
        if (message.text() != null && !message.text().isBlank()) {
            content.add(ResponseInputContent.ofInputText(
                    ResponseInputText.builder().text(message.text()).build()));
        }
        for (ModelInputItem.ImageInput image : message.images()) {
            if (image.imageUrl() == null || image.imageUrl().isBlank()) {
                continue;
            }
            content.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .imageUrl(image.imageUrl())
                    .detail(imageDetail(image.detail()))
                    .build()));
        }
        if (content.isEmpty()) {
            content.add(ResponseInputContent.ofInputText(
                    ResponseInputText.builder().text("").build()));
        }
        return content;
    }

    private ResponseInputImage.Detail imageDetail(String detail) {
        if (detail == null) {
            return ResponseInputImage.Detail.AUTO;
        }
        return switch (detail.toLowerCase(java.util.Locale.ROOT)) {
            case "low" -> ResponseInputImage.Detail.LOW;
            case "high" -> ResponseInputImage.Detail.HIGH;
            case "original" -> ResponseInputImage.Detail.ORIGINAL;
            default -> ResponseInputImage.Detail.AUTO;
        };
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
            case LOW -> ReasoningEffort.LOW;
            case MEDIUM -> ReasoningEffort.MEDIUM;
            case HIGH -> ReasoningEffort.HIGH;
            case XHIGH -> ReasoningEffort.XHIGH;
            case MAX -> ReasoningEffort.MAX;
        };
    }

    private ModelResponse toModelResponse(
            Response response, SummaryStreamTracker summaryTracker, long startedAt) {
        String text = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(ResponseOutputText::text)
                .collect(Collectors.joining());

        List<ModelReasoning> reasoning = response.output().stream()
                .filter(ResponseOutputItem::isReasoning)
                .map(ResponseOutputItem::asReasoning)
                .map(item -> {
                    List<String> summary = item.summary().stream()
                            .map(ResponseReasoningItem.Summary::text)
                            .toList();
                    if (summary.isEmpty()) {
                        summary = summaryTracker.summariesFor(
                                item._id().asString().orElse(null));
                    }
                    return new ModelReasoning(
                            item.encryptedContent().orElse(null),
                            item.content()
                                    .map(parts -> parts.stream()
                                            .map(ResponseReasoningItem.Content::text)
                                            .collect(Collectors.joining("\n")))
                                    .orElse(""),
                            summary);
                })
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

    /**
     * Forwards streamed reasoning summary increments while preserving part boundaries and falling
     * back to completion events when a gateway skips incremental summary deltas.
     */
    private static final class SummaryStreamTracker {

        private final ModelStreamListener listener;
        private final Set<String> streamedKeys = new HashSet<>();
        private final Map<String, Map<Long, StringBuilder>> partsByItem = new LinkedHashMap<>();
        private boolean emittedAny;
        private boolean endsWithNewline;

        private SummaryStreamTracker(ModelStreamListener listener) {
            this.listener = listener == null ? ModelStreamListener.NOOP : listener;
        }

        private void accept(ResponseStreamEvent event) {
            event.reasoningSummaryPartAdded().ifPresent(ignored -> {
                if (emittedAny && !endsWithNewline) {
                    listener.onReasoningSummaryDelta("\n");
                    endsWithNewline = true;
                }
            });
            event.reasoningSummaryTextDelta().ifPresent(delta -> {
                streamedKeys.add(summaryKey(delta.itemId(), delta.summaryIndex()));
                appendPart(delta.itemId(), delta.summaryIndex(), delta.delta());
                emitChunk(delta.delta());
            });
            event.reasoningSummaryTextDone().ifPresent(done ->
                    emitFallback(done.itemId(), done.summaryIndex(), done.text()));
            event.reasoningSummaryPartDone().ifPresent(done ->
                    done.part()._text().asString().ifPresent(partText ->
                            emitFallback(done.itemId(), done.summaryIndex(), partText)));
        }

        private void appendPart(String itemId, long summaryIndex, String chunk) {
            if (itemId == null || chunk == null || chunk.isEmpty()) {
                return;
            }
            partsByItem
                    .computeIfAbsent(itemId, ignored -> new java.util.TreeMap<>())
                    .computeIfAbsent(summaryIndex, ignored -> new StringBuilder())
                    .append(chunk);
        }

        private void emitFallback(String itemId, long summaryIndex, String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            if (itemId != null) {
                partsByItem
                        .computeIfAbsent(itemId, ignored -> new java.util.TreeMap<>())
                        .put(summaryIndex, new StringBuilder(text));
            }
            if (!streamedKeys.add(summaryKey(itemId, summaryIndex))) {
                return;
            }
            if (emittedAny && !endsWithNewline) {
                listener.onReasoningSummaryDelta("\n");
                endsWithNewline = true;
            }
            emitChunk(text);
        }

        private void emitChunk(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            listener.onReasoningSummaryDelta(chunk);
            emittedAny = true;
            endsWithNewline = chunk.endsWith("\n");
        }

        private List<String> summariesFor(String itemId) {
            if (itemId == null) {
                return List.of();
            }
            Map<Long, StringBuilder> parts = partsByItem.get(itemId);
            if (parts == null || parts.isEmpty()) {
                return List.of();
            }
            return parts.values().stream()
                    .map(StringBuilder::toString)
                    .filter(text -> !text.isBlank())
                    .toList();
        }

        private static String summaryKey(String itemId, long summaryIndex) {
            return itemId + ":" + summaryIndex;
        }
    }
}
