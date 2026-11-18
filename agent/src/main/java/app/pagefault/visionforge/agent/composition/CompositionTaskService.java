package app.pagefault.visionforge.agent.composition;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.generation.GenerationStatus;
import app.pagefault.visionforge.agent.generation.GenerationTask;
import app.pagefault.visionforge.agent.generation.GenerationTaskMapper;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequest;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequestMapper;
import app.pagefault.visionforge.agent.runtime.WorkflowStartStatus;
import app.pagefault.visionforge.agent.runtime.WorkflowStartType;
import app.pagefault.visionforge.agent.storyboard.StoryboardRange;
import app.pagefault.visionforge.agent.storyboard.StoryboardService;
import app.pagefault.visionforge.agent.storyboard.StoryboardShot;
import app.pagefault.visionforge.agent.storyboard.StoryboardVideoSelection;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.asset.media.AssetStatus;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class CompositionTaskService {

    private static final String DEFAULT_OUTPUT_SPEC =
            "{\"width\":1280,\"height\":720,\"fps\":30,\"videoCodec\":\"h264\","
                    + "\"crf\":23,\"preset\":\"veryfast\"}";
    private static final int MAX_SHOTS = 200;

    private final CompositionTaskMapper taskMapper;
    private final CompositionInputMapper inputMapper;
    private final WorkflowStartRequestMapper startRequestMapper;
    private final ConversationReferenceChecker conversationReferenceChecker;
    private final StoryboardService storyboardService;
    private final GenerationTaskMapper generationTaskMapper;
    private final AssetService assetService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    @Transactional
    public CompositionSubmission submit(UUID userId, SubmitCompositionCommand command) {
        if (command == null
                || command.conversationId() == null
                || command.storyboardFileId() == null) {
            throw new BusinessException(AgentErrorCode.GENERATION_INPUT_INVALID, "成片请求缺少会话或分镜");
        }
        conversationReferenceChecker.requireOwned(userId, command.conversationId());
        StoryboardService.ResolvedDocument resolved = storyboardService.resolveDocument(
                userId,
                command.conversationId(),
                command.storyboardFileId(),
                command.storyboardVersion());
        List<StoryboardShot> shots = new ArrayList<>(resolved.document().shots());
        shots.sort(java.util.Comparator.comparingInt(StoryboardShot::order));
        if (shots.isEmpty() || shots.size() > MAX_SHOTS) {
            throw invalid("分镜镜头数量不合法");
        }

        Instant now = clock.instant();
        UUID taskId = idGenerator.nextId();
        String workflowId = workflowId(taskId);
        String outputSpec = normalizeOutputSpec(command.outputSpec());

        CompositionTask task = new CompositionTask();
        task.setId(taskId);
        task.setUserId(userId);
        task.setConversationId(command.conversationId());
        task.setStoryboardFileId(command.storyboardFileId());
        task.setStoryboardVersion(resolved.storyboardVersion());
        task.setOutputSpec(outputSpec);
        task.setStatus(CompositionStatus.PENDING);
        task.setWorkflowId(workflowId);
        task.setStateVersion(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);

        int ordinal = 0;
        for (StoryboardShot shot : shots) {
            StoryboardVideoSelection selected = shot.selectedVideo();
            if (selected == null) {
                throw invalid("镜头 " + shot.id() + " 尚未选择视频");
            }
            GenerationTask generation = generationTaskMapper.selectById(selected.taskId());
            if (generation == null
                    || !userId.equals(generation.getUserId())
                    || !command.conversationId().equals(generation.getConversationId())
                    || generation.getType() != GenerationType.VIDEO
                    || generation.getStatus() != GenerationStatus.SUCCEEDED) {
                throw invalid("镜头 " + shot.id() + " 的视频任务不存在或尚未交付");
            }
            Asset asset = assetService.getForConversation(
                    userId, command.conversationId(), selected.assetId());
            if (asset.getStatus() != AssetStatus.READY) {
                throw invalid("镜头 " + shot.id() + " 的视频资产尚未就绪");
            }
            StoryboardRange range = shot.usedRange() == null
                    ? new StoryboardRange(
                            BigDecimal.ZERO, BigDecimal.valueOf(shot.durationSeconds()))
                    : shot.usedRange();

            CompositionInput input = new CompositionInput();
            input.setId(idGenerator.nextId());
            input.setTaskId(taskId);
            input.setShotId(shot.id());
            input.setGenerationTaskId(generation.getId());
            input.setAssetId(asset.getId());
            input.setFileVersionId(null);
            input.setStartSeconds(range.startSeconds());
            input.setEndSeconds(range.endSeconds());
            input.setOrdinal(ordinal++);
            input.setCreatedAt(now);
            inputMapper.insert(input);
        }

        createStartRequest(taskId, workflowId, now);
        return new CompositionSubmission(task, workflowId, true);
    }

    @Transactional
    public CompositionSubmission retry(UUID userId, UUID taskId) {
        CompositionTask task = getForUser(userId, taskId);
        if (task.getStatus() != CompositionStatus.FAILED
                && task.getStatus() != CompositionStatus.CANCELLED) {
            throw new BusinessException(
                    AgentErrorCode.GENERATION_STATE_CONFLICT,
                    "只有失败或取消的成片任务可以重试");
        }
        Instant now = clock.instant();
        String workflowId = workflowId(taskId) + "-" + UUID.randomUUID();
        task.setStatus(CompositionStatus.PENDING);
        task.setWorkflowId(workflowId);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        createStartRequest(taskId, workflowId, now);
        return new CompositionSubmission(task, workflowId, true);
    }

    private void createStartRequest(UUID taskId, String workflowId, Instant now) {
        WorkflowStartRequest startRequest = new WorkflowStartRequest();
        startRequest.setId(idGenerator.nextId());
        startRequest.setWorkflowType(WorkflowStartType.VIDEO_COMPOSITION);
        startRequest.setBusinessId(taskId);
        startRequest.setInputSchemaVersion(1);
        startRequest.setWorkflowId(workflowId);
        startRequest.setStatus(WorkflowStartStatus.PENDING);
        startRequest.setAttempts(0);
        startRequest.setCreatedAt(now);
        startRequest.setUpdatedAt(now);
        startRequestMapper.insert(startRequest);
    }

    public CompositionTask getForUser(UUID userId, UUID taskId) {
        CompositionTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException(AgentErrorCode.GENERATION_NOT_FOUND);
        }
        return task;
    }

    public CompositionTask get(UUID taskId) {
        CompositionTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(AgentErrorCode.GENERATION_NOT_FOUND);
        }
        return task;
    }

    public List<CompositionInput> inputs(UUID taskId) {
        return inputMapper.selectList(
                Wrappers.<CompositionInput>lambdaQuery()
                        .eq(CompositionInput::getTaskId, taskId)
                        .orderByAsc(CompositionInput::getOrdinal));
    }

    @Transactional
    public CompositionTask cancel(UUID userId, UUID taskId) {
        CompositionTask task = getForUser(userId, taskId);
        if (task.getStatus().terminal()) {
            return task;
        }
        Instant now = clock.instant();
        task.setStatus(CompositionStatus.CANCELLED);
        task.setErrorMessage("用户取消");
        task.setCompletedAt(now);
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public void markComposing(UUID taskId) {
        CompositionTask task = get(taskId);
        if (task.getStatus().terminal()) {
            return;
        }
        Instant now = clock.instant();
        task.setStatus(CompositionStatus.COMPOSING);
        task.setStartedAt(now);
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
    }

    @Transactional
    public void markUploading(UUID taskId, String uploadId) {
        CompositionTask task = get(taskId);
        if (task.getStatus().terminal()) {
            return;
        }
        task.setStatus(CompositionStatus.UPLOADING);
        task.setMultipartUploadId(uploadId);
        task.setUpdatedAt(clock.instant());
        taskMapper.updateById(task);
    }

    @Transactional
    public void markPublishing(UUID taskId) {
        CompositionTask task = get(taskId);
        if (task.getStatus().terminal()) {
            return;
        }
        task.setStatus(CompositionStatus.PUBLISHING);
        task.setUpdatedAt(clock.instant());
        taskMapper.updateById(task);
    }

    @Transactional
    public void markSucceeded(
            UUID taskId, UUID assetId, UUID fileId, UUID versionId) {
        CompositionTask task = get(taskId);
        Instant now = clock.instant();
        task.setStatus(CompositionStatus.SUCCEEDED);
        task.setOutputAssetId(assetId);
        task.setOutputFileId(fileId);
        task.setOutputVersionId(versionId);
        task.setErrorMessage(null);
        task.setStateVersion(task.getStateVersion() + 1);
        task.setCompletedAt(now);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
    }

    @Transactional
    public void markFailed(UUID taskId, String errorMessage) {
        CompositionTask task = get(taskId);
        if (task.getStatus().terminal()) {
            return;
        }
        Instant now = clock.instant();
        task.setStatus(CompositionStatus.FAILED);
        task.setErrorMessage(truncate(errorMessage));
        task.setStateVersion(task.getStateVersion() + 1);
        task.setCompletedAt(now);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
    }

    public boolean cancelled(UUID taskId) {
        return get(taskId).getStatus() == CompositionStatus.CANCELLED;
    }

    public List<WorkflowStartRequest> pendingStartRequests(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return startRequestMapper.selectList(
                Wrappers.<WorkflowStartRequest>lambdaQuery()
                        .eq(WorkflowStartRequest::getWorkflowType,
                                WorkflowStartType.VIDEO_COMPOSITION)
                        .eq(WorkflowStartRequest::getStatus, WorkflowStartStatus.PENDING)
                        .orderByAsc(WorkflowStartRequest::getCreatedAt)
                        .last("LIMIT " + safeLimit));
    }

    public void markWorkflowStarted(String workflowId) {
        WorkflowStartRequest request = findStartRequest(workflowId);
        if (request == null) {
            return;
        }
        request.setStatus(WorkflowStartStatus.STARTED);
        request.setAttempts(request.getAttempts() + 1);
        request.setLastError(null);
        request.setUpdatedAt(clock.instant());
        startRequestMapper.updateById(request);
    }

    public void markWorkflowStartRetryable(String workflowId, String errorMessage) {
        WorkflowStartRequest request = findStartRequest(workflowId);
        if (request == null) {
            return;
        }
        request.setStatus(WorkflowStartStatus.PENDING);
        request.setAttempts(request.getAttempts() + 1);
        request.setLastError(truncate(errorMessage));
        request.setUpdatedAt(clock.instant());
        startRequestMapper.updateById(request);
    }

    @Transactional
    public void recordWorkflowStartFailure(
            WorkflowStartRequest request, String errorMessage, int maxAttempts) {
        int attempts = request.getAttempts() + 1;
        request.setAttempts(attempts);
        request.setLastError(truncate(errorMessage));
        request.setUpdatedAt(clock.instant());
        if (attempts >= Math.max(maxAttempts, 1)) {
            request.setStatus(WorkflowStartStatus.FAILED);
            markFailed(request.getBusinessId(), "成片任务启动失败: " + errorMessage);
        } else {
            request.setStatus(WorkflowStartStatus.PENDING);
        }
        startRequestMapper.updateById(request);
    }

    public static String workflowId(UUID taskId) {
        return "video-composition-" + taskId;
    }

    private WorkflowStartRequest findStartRequest(String workflowId) {
        return startRequestMapper.selectOne(
                Wrappers.<WorkflowStartRequest>lambdaQuery()
                        .eq(WorkflowStartRequest::getWorkflowId, workflowId));
    }

    private String normalizeOutputSpec(String outputSpec) {
        String raw = outputSpec == null || outputSpec.isBlank()
                ? DEFAULT_OUTPUT_SPEC
                : outputSpec.trim();
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw invalid("成片输出规格必须是 JSON 对象");
            }
            return objectMapper.writeValueAsString(node);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("成片输出规格必须是合法 JSON");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(
                AgentErrorCode.GENERATION_INPUT_INVALID, message);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
}
