package app.pagefault.visionforge.agent.provider.media;

import java.util.List;
import java.util.Locale;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bailian Wanxiang text-to-image task route. Submission and polling follow the DashScope async
 * task contract; the result URL is downloaded and archived like any other provider artifact.
 */
public class DashScopeT2iProvider implements RemoteMediaProvider {

    private static final String SUBMIT_PATH = "/services/aigc/text2image/image-synthesis";
    private static final String TASK_PATH = "/tasks/";

    private final ObjectMapper objectMapper;
    private final DashScopeHttpClient http;
    private final MediaResultDownloader downloader;

    public DashScopeT2iProvider(ObjectMapper objectMapper, MediaResultDownloader downloader) {
        this.objectMapper = objectMapper;
        this.http = new DashScopeHttpClient(objectMapper);
        this.downloader = downloader;
    }

    @Override
    public MediaProtocol protocol() {
        return MediaProtocol.DASHSCOPE_T2I;
    }

    @Override
    public MediaProviderOutcome start(MediaProviderRequest request) {
        if (request.type() != GenerationType.IMAGE) {
            throw unsupported("百炼文生图适配器只接受图片任务");
        }
        JsonNode body = http.post(request, SUBMIT_PATH, buildBody(request), true);
        String taskId = body.path("output").path("task_id").asText("");
        if (taskId.isBlank()) {
            throw failure("百炼文生图任务没有返回 task_id");
        }
        return new MediaProviderOutcome.Pending(
                new MediaProviderReceipt(
                        request.taskId().toString(),
                        taskId,
                        body.path("output").path("task_status").asText("PENDING"),
                        usage(body)),
                taskId);
    }

    @Override
    public MediaProviderOutcome query(MediaProviderRequest request, String remoteTaskId) {
        JsonNode body = http.get(request, TASK_PATH + remoteTaskId);
        JsonNode output = body.path("output");
        String status = output.path("task_status").asText("").toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCEEDED" -> completed(request, remoteTaskId, body, output);
            case "FAILED" -> new MediaProviderOutcome.Failed(
                    status, errorMessage(output, "百炼文生图任务失败"));
            case "CANCELED", "CANCELLED" -> new MediaProviderOutcome.Cancelled(
                    "百炼文生图任务已取消");
            default -> new MediaProviderOutcome.Pending(
                    receipt(request, remoteTaskId, body, status.isBlank() ? "UNKNOWN" : status),
                    remoteTaskId);
        };
    }

    ObjectNode buildBody(MediaProviderRequest request) {
        JsonNode parameters = readParameters(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelId());
        ObjectNode input = root.putObject("input");
        input.put("prompt", request.prompt());
        copy(parameters, input, "negative_prompt", "negativePrompt");
        ObjectNode output = root.putObject("parameters");
        copy(parameters, output, "size");
        copy(parameters, output, "n");
        copy(parameters, output, "seed");
        copy(parameters, output, "prompt_extend", "promptExtend");
        copy(parameters, output, "watermark");
        return root;
    }

    private MediaProviderOutcome completed(
            MediaProviderRequest request, String remoteTaskId, JsonNode body, JsonNode output) {
        JsonNode results = output.path("results");
        if (results.isArray()) {
            for (JsonNode result : results) {
                String url = result.path("url").asText("");
                if (!url.isBlank()) {
                    byte[] bytes = downloader.download(url);
                    return new MediaProviderOutcome.Completed(
                            receipt(request, remoteTaskId, body, "SUCCEEDED"),
                            List.of(new MediaArtifact(0, mimeType(url), bytes)));
                }
            }
        }
        return new MediaProviderOutcome.Failed(
                "SUCCEEDED", "百炼文生图任务成功但没有返回图片地址");
    }

    private MediaProviderReceipt receipt(
            MediaProviderRequest request, String remoteTaskId, JsonNode body, String status) {
        String requestId = body.path("request_id").asText("");
        return new MediaProviderReceipt(
                requestId.isBlank() ? request.taskId().toString() : requestId,
                remoteTaskId,
                status,
                usage(body));
    }

    private String usage(JsonNode body) {
        JsonNode usage = body.path("usage");
        return usage.isMissingNode() || usage.isNull() ? "{}" : write(usage);
    }

    private String errorMessage(JsonNode output, String fallback) {
        String message = output.path("message").asText("");
        String code = output.path("code").asText("");
        if (message.isBlank() && code.isBlank()) {
            return fallback;
        }
        return code.isBlank() ? message : code + ": " + message;
    }

    private JsonNode readParameters(MediaProviderRequest request) {
        try {
            return objectMapper.readTree(
                    request.parameters() == null ? "{}" : request.parameters());
        } catch (RuntimeException exception) {
            throw unsupported("百炼文生图参数不是合法 JSON");
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
