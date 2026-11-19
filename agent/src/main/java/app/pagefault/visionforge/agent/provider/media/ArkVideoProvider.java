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
 * Ark video task adapter (Seedance) speaking the documented {@code /api/v3/contents/generations/tasks}
 * JSON contract directly over HTTP. Auto-retry is handled at the Temporal workflow layer.
 */
public class ArkVideoProvider implements RemoteMediaProvider {

    private static final String TASKS_PATH = "/api/v3/contents/generations/tasks";
    private static final long MAX_RESULT_BYTES = 1024L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final ArkClientFactory clients;
    private final HttpMediaDownloader downloader;

    public ArkVideoProvider(ObjectMapper objectMapper, ArkClientFactory clients) {
        this(objectMapper, clients, "");
    }

    public ArkVideoProvider(
            ObjectMapper objectMapper, ArkClientFactory clients, String userAgent) {
        this(objectMapper, clients, new HttpMediaDownloader(MAX_RESULT_BYTES, userAgent));
    }

    ArkVideoProvider(
            ObjectMapper objectMapper,
            ArkClientFactory clients,
            HttpMediaDownloader downloader) {
        this.objectMapper = objectMapper;
        this.clients = clients;
        this.downloader = downloader;
    }

    @Override
    public MediaProtocol protocol() {
        return MediaProtocol.ARK_VIDEO_TASKS;
    }

    @Override
    public MediaProviderOutcome start(MediaProviderRequest request) {
        if (request.type() != GenerationType.VIDEO) {
            throw unsupported("Ark 视频适配器只接受视频任务");
        }
        try {
            ObjectNode payload = createRequest(request);
            JsonNode response = clients.post(request, TASKS_PATH, payload, objectMapper);
            String remoteTaskId = response.path("id").asText("");
            if (remoteTaskId.isBlank()) {
                throw failure("Ark 视频任务没有返回远端任务 ID");
            }
            return new MediaProviderOutcome.Pending(
                    new MediaProviderReceipt(
                            request.taskId().toString(),
                            remoteTaskId,
                            "queued",
                            "{}"),
                    remoteTaskId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("Ark 视频提交失败：" + message(exception));
        }
    }

    @Override
    public MediaProviderOutcome query(MediaProviderRequest request, String remoteTaskId) {
        try {
            JsonNode task = clients.get(request, TASKS_PATH + "/" + remoteTaskId, objectMapper);
            String status = task.path("status").asText("").toLowerCase(Locale.ROOT);
            if (status.isBlank() || "queued".equals(status) || "running".equals(status)) {
                return new MediaProviderOutcome.Pending(
                        receipt(request, remoteTaskId, status.isBlank() ? "unknown" : status),
                        remoteTaskId);
            }
            if ("cancelled".equals(status) || "canceled".equals(status)) {
                return new MediaProviderOutcome.Cancelled("Ark 视频任务已取消");
            }
            if ("failed".equals(status)) {
                JsonNode error = task.path("error");
                String detail = error.isMissingNode() || error.isNull()
                        ? "上游任务失败"
                        : error.path("code").asText("error")
                                + ": "
                                + error.path("message").asText("上游任务失败");
                return new MediaProviderOutcome.Failed("failed", detail);
            }
            if ("succeeded".equals(status)) {
                String videoUrl = task.path("content").path("video_url").asText("");
                if (videoUrl.isBlank()) {
                    throw failure("Ark 视频任务成功但没有返回 video_url");
                }
                byte[] bytes = downloader.download(videoUrl);
                return new MediaProviderOutcome.Completed(
                        receipt(request, remoteTaskId, "succeeded"),
                        List.of(new MediaArtifact(0, "video/mp4", bytes)));
            }
            return new MediaProviderOutcome.Pending(
                    receipt(request, remoteTaskId, status), remoteTaskId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("Ark 视频查询失败：" + message(exception));
        }
    }

    ObjectNode createRequest(MediaProviderRequest request) {
        JsonNode parameters = readParameters(request);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelId());
        ArrayNode content = root.putArray("content");

        ObjectNode textItem = content.addObject();
        textItem.put("type", "text");
        textItem.put("text", request.prompt());

        for (MediaProviderInput input : request.inputs()) {
            String payload = MediaProviderInputs.inlineOrUrl(input);
            if (payload == null || payload.isBlank()) {
                throw unsupported("视频输入素材缺少可访问 URL");
            }
            ObjectNode imageItem = content.addObject();
            imageItem.put("type", "image_url");
            imageItem.putObject("image_url").put("url", payload);
            imageItem.put("role", role(input.role()));
        }

        String resolution = text(parameters, "resolution");
        if (resolution != null && !resolution.isBlank()) {
            // Ark only accepts 480p / 720p / 1080p; models may emit either casing.
            root.put("resolution", resolution.trim().toLowerCase(Locale.ROOT));
        }
        String ratio = text(parameters, "ratio", "aspectRatio");
        if (ratio != null) {
            root.put("ratio", ratio);
        }
        Long duration = longValue(parameters, "durationSeconds", "duration");
        if (duration != null) {
            root.put("duration", duration);
        }
        Boolean generateAudio = booleanValue(parameters, "generateAudio", "generate_audio");
        if (generateAudio != null) {
            root.put("generate_audio", generateAudio);
        }
        Boolean watermark = booleanValue(parameters, "watermark");
        if (watermark != null) {
            root.put("watermark", watermark);
        }
        Long seed = longValue(parameters, "seed");
        if (seed != null) {
            root.put("seed", seed);
        }
        return root;
    }

    private MediaProviderReceipt receipt(
            MediaProviderRequest request, String remoteTaskId, String status) {
        return new MediaProviderReceipt(
                request.taskId().toString(), remoteTaskId, status, "{}");
    }

    private String role(GenerationInputRole role) {
        return switch (role) {
            case FIRST_FRAME -> "first_frame";
            case LAST_FRAME -> "last_frame";
            case REFERENCE, INIT_IMAGE, MASK -> "reference_image";
        };
    }

    private JsonNode readParameters(MediaProviderRequest request) {
        try {
            return objectMapper.readTree(
                    request.parameters() == null ? "{}" : request.parameters());
        } catch (RuntimeException exception) {
            throw unsupported("Ark 视频参数不是合法 JSON");
        }
    }

    private String text(JsonNode parameters, String... fields) {
        for (String field : fields) {
            JsonNode value = parameters.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private Long longValue(JsonNode parameters, String... fields) {
        for (String field : fields) {
            JsonNode value = parameters.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asLong();
            }
        }
        return null;
    }

    private Boolean booleanValue(JsonNode parameters, String... fields) {
        for (String field : fields) {
            JsonNode value = parameters.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asBoolean();
            }
        }
        return null;
    }

    private BusinessException unsupported(String message) {
        return new BusinessException(
                AgentErrorCode.GENERATION_CAPABILITY_UNSUPPORTED, message);
    }

    private BusinessException failure(String message) {
        return new BusinessException(
                AgentErrorCode.GENERATION_PROVIDER_ERROR, message);
    }

    private String message(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
