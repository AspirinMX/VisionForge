package app.pagefault.visionforge.agent.provider.media;

import java.util.List;
import java.util.Locale;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.generation.GenerationInputRole;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bailian Wanxiang multimodal image generation ({@code multimodal-generation/generation}). This
 * endpoint returns a result in the same request, so it is an {@code INLINE_RESULT} route.
 */
public class DashScopeImageMessagesProvider implements MediaProvider {

    private static final String PATH = "/services/aigc/multimodal-generation/generation";

    private final ObjectMapper objectMapper;
    private final DashScopeHttpClient http;
    private final MediaResultDownloader downloader;

    public DashScopeImageMessagesProvider(
            ObjectMapper objectMapper, MediaResultDownloader downloader) {
        this.objectMapper = objectMapper;
        this.http = new DashScopeHttpClient(objectMapper);
        this.downloader = downloader;
    }

    @Override
    public MediaProtocol protocol() {
        return MediaProtocol.DASHSCOPE_IMAGE_MESSAGES;
    }

    @Override
    public MediaProviderOutcome start(MediaProviderRequest request) {
        if (request.type() != GenerationType.IMAGE) {
            throw unsupported("百炼图片适配器只接受图片任务");
        }
        JsonNode body = http.post(request, PATH, buildBody(request), false);
        String imageUrl = extractImageUrl(body);
        if (imageUrl == null) {
            throw failure("百炼图片接口没有返回图片地址");
        }
        byte[] bytes = downloader.download(imageUrl);
        return new MediaProviderOutcome.Completed(
                new MediaProviderReceipt(
                        requestId(request, body),
                        null,
                        "succeeded",
                        usage(body)),
                List.of(new MediaArtifact(0, mimeType(imageUrl), bytes)));
    }

    JsonNode buildBody(MediaProviderRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelId());

        ObjectNode input = root.putObject("input");
        ArrayNode messages = input.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        content.addObject().put("text", request.prompt());
        for (MediaProviderInput item : request.inputs()) {
            if (item.role() == GenerationInputRole.MASK) {
                throw unsupported("百炼图片暂不支持 mask 输入");
            }
            if (item.url() == null || item.url().isBlank()) {
                throw unsupported("百炼图片参考素材缺少可访问 URL");
            }
            content.addObject().put("image", item.url());
        }

        JsonNode parameters = readParameters(request);
        ObjectNode output = root.putObject("parameters");
        copy(parameters, output, "size");
        copy(parameters, output, "n");
        copy(parameters, output, "seed");
        copy(parameters, output, "watermark");
        copy(parameters, output, "prompt_extend", "promptExtend");
        copy(parameters, output, "negative_prompt", "negativePrompt");
        copy(parameters, output, "thinking");
        return root;
    }

    String extractImageUrl(JsonNode body) {
        JsonNode choices = body.path("output").path("choices");
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode content = choice.path("message").path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (JsonNode item : content) {
                    for (String field : List.of("image", "image_url", "url")) {
                        String value = item.path(field).asText("");
                        if (!value.isBlank()) {
                            return value;
                        }
                    }
                }
            }
        }
        JsonNode results = body.path("output").path("results");
        if (results.isArray()) {
            for (JsonNode result : results) {
                String value = result.path("url").asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String requestId(MediaProviderRequest request, JsonNode body) {
        String id = body.path("request_id").asText("");
        return id.isBlank() ? request.taskId().toString() : id;
    }

    private String usage(JsonNode body) {
        JsonNode usage = body.path("usage");
        return usage.isMissingNode() || usage.isNull() ? "{}" : write(usage);
    }

    private JsonNode readParameters(MediaProviderRequest request) {
        try {
            return objectMapper.readTree(
                    request.parameters() == null ? "{}" : request.parameters());
        } catch (RuntimeException exception) {
            throw unsupported("百炼图片参数不是合法 JSON");
        }
    }

    private void copy(JsonNode source, ObjectNode target, String field, String... aliases) {
        JsonNode value = source.path(field);
        if (value.isMissingNode() || value.isNull()) {
            for (String alias : aliases) {
                value = source.path(alias);
                if (!value.isMissingNode() && !value.isNull()) {
                    break;
                }
            }
        }
        if (!value.isMissingNode() && !value.isNull()) {
            target.set(field, value);
        }
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            return "{}";
        }
    }

    private String mimeType(String url) {
        String path = url.toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (path.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private BusinessException unsupported(String message) {
        return new BusinessException(AgentErrorCode.GENERATION_CAPABILITY_UNSUPPORTED, message);
    }

    private BusinessException failure(String message) {
        return new BusinessException(AgentErrorCode.GENERATION_PROVIDER_ERROR, message);
    }
}
