package app.pagefault.visionforge.agent.provider.media;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.volcengine.ark.runtime.models.content_generation.ContentGenerationTask;
import com.volcengine.ark.runtime.models.content_generation.ContentItem;
import com.volcengine.ark.runtime.models.content_generation.ContentType;
import com.volcengine.ark.runtime.models.content_generation.CreateContentGenerationTaskRequest;
import com.volcengine.ark.runtime.models.content_generation.CreateContentGenerationTaskResponse;
import com.volcengine.ark.runtime.models.content_generation.ImageURL;
import com.volcengine.ark.runtime.models.content_generation.TaskStatus;
import com.volcengine.ark.runtime.service.ArkService;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.generation.GenerationInputRole;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ark video task adapter (Seedance). The SDK owns the HTTP protocol; this class only maps the
 * domain request, persists the remote task id and maps status. Auto-retry is disabled.
 */
@RequiredArgsConstructor
public class ArkVideoProvider implements RemoteMediaProvider {

    private static final int MAX_CACHED_CLIENTS = 16;
    private static final long MAX_RESULT_BYTES = 1024L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpMediaDownloader downloader = new HttpMediaDownloader(MAX_RESULT_BYTES);
    private final Map<String, ArkService> clients = new ConcurrentHashMap<>();

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
            List<ContentItem> content = new ArrayList<>();
            content.add(ContentItem.builder()
                    .type(ContentType.TEXT)
                    .text(request.prompt())
                    .build());
            for (MediaProviderInput input : request.inputs()) {
                if (input.url() == null || input.url().isBlank()) {
                    throw unsupported("视频输入素材缺少可访问 URL");
                }
                content.add(ContentItem.builder()
                        .type(ContentType.IMAGE_URL)
                        .imageUrl(ImageURL.builder().url(input.url()).build())
                        .role(role(input.role()))
                        .build());
            }

            JsonNode parameters = objectMapper.readTree(
                    request.parameters() == null ? "{}" : request.parameters());
            CreateContentGenerationTaskRequest.Builder builder =
                    CreateContentGenerationTaskRequest.builder()
                            .model(request.modelId())
                            .content(content);
            String resolution = text(parameters, "resolution");
            if (resolution != null) {
                builder.resolution(resolution);
            }
            String ratio = text(parameters, "ratio");
            if (ratio != null) {
                builder.ratio(ratio);
            }
            Long duration = longValue(parameters, "durationSeconds");
            if (duration != null) {
                builder.duration(duration);
            }
            Boolean generateAudio = booleanValue(parameters, "generateAudio");
            if (generateAudio != null) {
                builder.generateAudio(generateAudio);
            }
            Boolean watermark = booleanValue(parameters, "watermark");
            if (watermark != null) {
                builder.watermark(watermark);
            }
            Long seed = longValue(parameters, "seed");
            if (seed != null) {
                builder.seed(seed);
            }

            CreateContentGenerationTaskResponse response =
                    client(request).createContentGenerationTask(builder.build());
            if (response == null || response.getId() == null || response.getId().isBlank()) {
                throw failure("Ark 视频任务没有返回远端任务 ID");
            }
            return new MediaProviderOutcome.Pending(
                    new MediaProviderReceipt(
                            request.taskId().toString(),
                            response.getId(),
                            "queued",
                            "{}"),
                    response.getId());
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("Ark 视频提交失败：" + message(exception));
        }
    }

    @Override
    public MediaProviderOutcome query(MediaProviderRequest request, String remoteTaskId) {
        try {
            ContentGenerationTask task = client(request).getContentGenerationTask(remoteTaskId);
            if (task == null || task.getStatus() == null) {
                return new MediaProviderOutcome.Pending(
                        receipt(request, remoteTaskId, "unknown"), remoteTaskId);
            }
            TaskStatus status = task.getStatus();
            if (status == TaskStatus.QUEUED || status == TaskStatus.RUNNING) {
                return new MediaProviderOutcome.Pending(
                        receipt(request, remoteTaskId, status.getValue()), remoteTaskId);
            }
            if (status == TaskStatus.CANCELLED) {
                return new MediaProviderOutcome.Cancelled("Ark 视频任务已取消");
            }
            if (status == TaskStatus.FAILED) {
                String detail = task.getError() == null
                        ? "上游任务失败"
                        : task.getError().getCode() + ": " + task.getError().getMessage();
                return new MediaProviderOutcome.Failed("failed", detail);
            }
            if (status == TaskStatus.SUCCEEDED) {
                if (task.getContent() == null
                        || task.getContent().getVideoUrl() == null
                        || task.getContent().getVideoUrl().isBlank()) {
                    throw failure("Ark 视频任务成功但没有返回 video_url");
                }
                byte[] bytes = downloader.download(task.getContent().getVideoUrl());
                return new MediaProviderOutcome.Completed(
                        receipt(request, remoteTaskId, "succeeded"),
                        List.of(new MediaArtifact(0, "video/mp4", bytes)));
            }
            return new MediaProviderOutcome.Pending(
                    receipt(request, remoteTaskId, status.getValue()), remoteTaskId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("Ark 视频查询失败：" + message(exception));
        }
    }

    private ArkService client(MediaProviderRequest request) {
        String key = request.baseUrl() + '|' + request.apiKey();
        ArkService cached = clients.get(key);
        if (cached != null) {
            return cached;
        }
        ArkService created = ArkService.builder()
                .baseUrl(request.baseUrl())
                .apiKey(request.apiKey())
                .retryTimes(0)
                .timeout(Duration.ofMinutes(15))
                .build();
        if (clients.size() >= MAX_CACHED_CLIENTS) {
            clients.clear();
        }
        ArkService existing = clients.putIfAbsent(key, created);
        return existing == null ? created : existing;
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

    private String text(JsonNode parameters, String field) {
        JsonNode value = parameters.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Long longValue(JsonNode parameters, String field) {
        JsonNode value = parameters.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private Boolean booleanValue(JsonNode parameters, String field) {
        JsonNode value = parameters.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asBoolean();
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
