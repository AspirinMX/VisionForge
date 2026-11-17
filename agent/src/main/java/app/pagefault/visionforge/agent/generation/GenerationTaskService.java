package app.pagefault.visionforge.agent.generation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ModelCapabilityType;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.provider.media.MediaArtifact;
import app.pagefault.visionforge.agent.provider.media.MediaProviderInput;
import app.pagefault.visionforge.agent.provider.media.MediaProviderReceipt;
import app.pagefault.visionforge.agent.provider.media.MediaProviderRegistry;
import app.pagefault.visionforge.agent.provider.media.MediaProviderRequest;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequest;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequestMapper;
import app.pagefault.visionforge.agent.runtime.WorkflowStartStatus;
import app.pagefault.visionforge.agent.runtime.WorkflowStartType;
import app.pagefault.visionforge.asset.library.ConversationFileKind;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.library.PublishedMediaFile;
import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetKind;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.billing.credit.CreditService;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Application service for the first-stage generation lifecycle.
 *
 * <p>Acceptance is one local transaction: task, frozen inputs, output placeholder, credit
 * reservation and workflow start request. Provider calls happen only in workflow activities.
 */
@RequiredArgsConstructor
public class GenerationTaskService {

    private static final int MAX_PROMPT_LENGTH = 32_000;
    private static final int MAX_PARAMETERS_LENGTH = 20_000;
    private static final int MAX_INPUTS = 8;
    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 100;

    private final GenerationTaskMapper taskMapper;
    private final GenerationAttemptMapper attemptMapper;
    private final GenerationInputMapper inputMapper;
    private final GenerationOutputMapper outputMapper;
    private final WorkflowStartRequestMapper startRequestMapper;
    private final ModelDefinitionService modelDefinitionService;
    private final ConversationReferenceChecker conversationReferenceChecker;
    private final AssetService assetService;
    private final ConversationFileService conversationFileService;
    private final CreditService creditService;
    private final MediaProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    @Transactional
    public GenerationSubmission submit(UUID userId, SubmitGenerationCommand command) {
        if (command == null || command.type() == null) {
            throw new BusinessException(
                    AgentErrorCode.GENERATION_CAPABILITY_UNSUPPORTED, "生成类型不能为空");
        }
        UUID conversationId = command.conversationId();
        conversationReferenceChecker.requireOwned(userId, conversationId);
        ModelCapabilityType capabilityType = capabilityOf(command.type());
        ModelDefinition model = command.modelDefinitionId() == null
                ? modelDefinitionService.requireDefaultEnabled(capabilityType)
                : modelDefinitionService.requireEnabled(
                        command.modelDefinitionId(), capabilityType);
        providerRegistry.require(model.getProtocol());

        String prompt = normalizePrompt(command.prompt());
        String parameters = normalizeParameters(command.parameters());
        List<ValidatedInput> inputs = validateInputs(
                userId, conversationId, command.type(), command.inputs());
        UUID sourceTaskId = validateSourceTask(userId, command.sourceTaskId());
        String fingerprint = fingerprint(
                conversationId, command.type(), model.getId(), prompt, parameters,
                inputs, sourceTaskId);

        String idempotencyKey = normalizeKey(command.idempotencyKey());
        if (idempotencyKey != null) {
            GenerationTask existing = findByIdempotencyKey(userId, idempotencyKey);
            if (existing != null) {
                if (!fingerprint.equals(existing.getRequestFingerprint())) {
                    throw new BusinessException(AgentErrorCode.GENERATION_IDEMPOTENCY_CONFLICT);
                }
                return new GenerationSubmission(existing, existing.getWorkflowId(), false);
            }
        }

        UUID taskId = idGenerator.nextId();
        String workflowId = workflowId(taskId);
        Instant now = clock.instant();
        GenerationTask task = new GenerationTask();
        task.setId(taskId);
        task.setUserId(userId);
        task.setConversationId(conversationId);
        task.setAgentRunId(command.agentRunId());
        task.setToolInvocationId(command.toolInvocationId());
        task.setType(command.type());
        task.setModelDefinitionId(model.getId());
        task.setProtocol(model.getProtocol());
        task.setExecutionMode(model.getExecutionMode());
        task.setPrompt(prompt);
        task.setParameters(parameters);
        task.setStatus(GenerationStatus.PENDING);
        task.setDeliveryStatus(GenerationDeliveryStatus.PENDING);
        task.setBillingStatus(GenerationBillingStatus.RESERVED);
        task.setCancelStatus(GenerationCancelStatus.NONE);
        task.setPriceUnits(model.getPriceUnits());
        task.setBudgetUnits(model.getPriceUnits());
        task.setIdempotencyKey(idempotencyKey);
        task.setRequestFingerprint(fingerprint);
        task.setSourceTaskId(sourceTaskId);
        task.setWorkflowId(workflowId);
        task.setStateVersion(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);

        GenerationOutput output = new GenerationOutput();
        output.setId(idGenerator.nextId());
        output.setTaskId(taskId);
        output.setOutputIndex(0);
        output.setStatus(GenerationOutputStatus.PENDING);
        output.setCreatedAt(now);
        output.setUpdatedAt(now);
        outputMapper.insert(output);

        int ordinal = 0;
        for (ValidatedInput input : inputs) {
            GenerationInput row = new GenerationInput();
            row.setId(idGenerator.nextId());
            row.setTaskId(taskId);
            row.setAssetId(input.assetId());
            row.setFileVersionId(input.fileVersionId());
            row.setRole(input.role());
            row.setOrdinal(ordinal++);
            row.setCreatedAt(now);
            inputMapper.insert(row);
        }

        creditService.reserve(userId, creditBusinessKey(taskId), model.getPriceUnits(), taskId);

        WorkflowStartRequest startRequest = new WorkflowStartRequest();
        startRequest.setId(idGenerator.nextId());
        startRequest.setWorkflowType(WorkflowStartType.MEDIA_GENERATION);
        startRequest.setBusinessId(taskId);
        startRequest.setInputSchemaVersion(1);
        startRequest.setWorkflowId(workflowId);
        startRequest.setStatus(WorkflowStartStatus.PENDING);
        startRequest.setAttempts(0);
        startRequest.setCreatedAt(now);
        startRequest.setUpdatedAt(now);
        startRequestMapper.insert(startRequest);

        return new GenerationSubmission(task, workflowId, true);
    }

    public GenerationTask getForUser(UUID userId, UUID taskId) {
        GenerationTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException(AgentErrorCode.GENERATION_NOT_FOUND);
        }
        return task;
    }

    public GenerationTask get(UUID taskId) {
        GenerationTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(AgentErrorCode.GENERATION_NOT_FOUND);
        }
        return task;
    }

    public List<GenerationTask> list(UUID userId, UUID conversationId, int limit) {
        return taskMapper.selectList(
                Wrappers.<GenerationTask>lambdaQuery()
                        .eq(GenerationTask::getUserId, userId)
                        .eq(conversationId != null, GenerationTask::getConversationId, conversationId)
                        .orderByDesc(GenerationTask::getCreatedAt)
                        .last("LIMIT " + safeLimit(limit)));
    }

    public List<GenerationInput> inputs(UUID taskId) {
        return inputMapper.selectList(
                Wrappers.<GenerationInput>lambdaQuery()
                        .eq(GenerationInput::getTaskId, taskId)
                        .orderByAsc(GenerationInput::getOrdinal));
    }

    public List<GenerationOutput> outputs(UUID taskId) {
        return outputMapper.selectList(
                Wrappers.<GenerationOutput>lambdaQuery()
                        .eq(GenerationOutput::getTaskId, taskId)
                        .orderByAsc(GenerationOutput::getOutputIndex));
    }

    public GenerationAttempt findAttempt(UUID taskId, int attemptNo) {
        return attemptMapper.selectOne(
                Wrappers.<GenerationAttempt>lambdaQuery()
                        .eq(GenerationAttempt::getTaskId, taskId)
                        .eq(GenerationAttempt::getAttemptNo, attemptNo));
    }

    public MediaProviderRequest providerRequest(GenerationTask task, List<GenerationInput> inputs) {
        ModelDefinition model = modelDefinitionService.get(task.getModelDefinitionId());
        List<MediaProviderInput> providerInputs = new ArrayList<>(inputs.size());
        for (GenerationInput input : inputs) {
            Asset asset = assetService.get(task.getUserId(), input.getAssetId());
            providerInputs.add(new MediaProviderInput(
                    input.getAssetId(), input.getRole(), asset.getMimeType(), null));
        }
        return new MediaProviderRequest(
                task.getId(),
                task.getType(),
                model.getModelId(),
                task.getPrompt(),
                task.getParameters(),
                task.getExecutionMode(),
                providerInputs);
    }

    /**
     * Marks the task SUBMITTING and returns the single attempt used by every retry of the same
     * provider call. A workflow replay therefore reuses the recorded attempt instead of submitting
     * a second paid request.
     */
    @Transactional
    public GenerationAttempt beginAttempt(UUID taskId) {
        GenerationTask task = get(taskId);
        GenerationAttempt existing = findAttempt(taskId, 1);
        if (existing != null) {
            return existing;
        }
        if (task.getStatus() != GenerationStatus.PENDING) {
            throw new BusinessException(AgentErrorCode.GENERATION_STATE_CONFLICT);
        }
        Instant now = clock.instant();
        GenerationAttempt attempt = new GenerationAttempt();
        attempt.setId(idGenerator.nextId());
        attempt.setTaskId(taskId);
        attempt.setAttemptNo(1);
        attempt.setProviderModelId(modelDefinitionService
                .get(task.getModelDefinitionId()).getModelId());
        attempt.setStatus(GenerationAttemptStatus.SUBMITTING);
        attempt.setSubmittedAt(now);
        attempt.setCreatedAt(now);
        attemptMapper.insert(attempt);

        task.setStatus(GenerationStatus.SUBMITTING);
        task.setStartedAt(now);
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        return attempt;
    }

    public void recordPending(
            UUID taskId, UUID attemptId, MediaProviderReceipt receipt, String remoteTaskId) {
        GenerationTask task = get(taskId);
        GenerationAttempt attempt = attemptMapper.selectById(attemptId);
        Instant now = clock.instant();
        if (attempt != null) {
            attempt.setStatus(GenerationAttemptStatus.RUNNING);
            attempt.setRequestId(receipt == null ? null : receipt.requestId());
            attempt.setRemoteTaskId(remoteTaskId);
            attempt.setUsagePayload(receipt == null ? null : receipt.usage());
            attemptMapper.updateById(attempt);
        }
        task.setStatus(GenerationStatus.RUNNING);
        task.setProviderStatus(receipt == null ? "running" : receipt.providerStatus());
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
    }

    /**
     * Archives provider output. Binary bytes are never returned to the workflow; each artifact is
     * written through the asset service and published as a conversation file here. Retrying uses
     * the persisted output/asset ids, so a worker restart cannot duplicate files.
     */
    public void recordCompleted(
            UUID taskId,
            UUID attemptId,
            MediaProviderReceipt receipt,
            List<MediaArtifact> artifacts) {
        GenerationTask task = get(taskId);
        if (artifacts == null || artifacts.isEmpty()) {
            throw new BusinessException(
                    AgentErrorCode.GENERATION_ARCHIVE_FAILED, "网关没有返回任何产物");
        }
        try {
            archiveArtifacts(task, attemptId, receipt, artifacts);
        } catch (RuntimeException exception) {
            task.setStatus(GenerationStatus.ARCHIVE_FAILED);
            task.setDeliveryStatus(GenerationDeliveryStatus.FAILED);
            task.setErrorMessage(truncate(exception.getMessage()));
            task.setStateVersion(task.getStateVersion() + 1);
            task.setUpdatedAt(clock.instant());
            taskMapper.updateById(task);
            throw exception;
        }
    }

    private void archiveArtifacts(
            GenerationTask task,
            UUID attemptId,
            MediaProviderReceipt receipt,
            List<MediaArtifact> artifacts) {
        UUID taskId = task.getId();
        List<GenerationOutput> outputs = outputs(taskId);
        UUID lastFileId = null;
        for (MediaArtifact artifact : artifacts) {
            GenerationOutput output = outputs.stream()
                    .filter(row -> row.getOutputIndex() == artifact.index())
                    .findFirst()
                    .orElse(null);
            if (output == null) {
                Instant now = clock.instant();
                output = new GenerationOutput();
                output.setId(idGenerator.nextId());
                output.setTaskId(taskId);
                output.setOutputIndex(artifact.index());
                output.setStatus(GenerationOutputStatus.PENDING);
                output.setCreatedAt(now);
                output.setUpdatedAt(now);
                outputMapper.insert(output);
            }
            if (output.getStatus() == GenerationOutputStatus.PUBLISHED) {
                lastFileId = output.getFileId();
                continue;
            }
            Asset asset = output.getAssetId() == null
                    ? assetService.createGeneratedAsset(
                            task.getUserId(),
                            task.getConversationId(),
                            taskId,
                            output.getId(),
                            assetKind(task.getType()),
                            artifact.mimeType(),
                            artifact.content())
                    : assetService.get(task.getUserId(), output.getAssetId());
            output.setAssetId(asset.getId());
            output.setStatus(GenerationOutputStatus.ARCHIVED);
            output.setUpdatedAt(clock.instant());
            outputMapper.updateById(output);

            ConversationFileKind fileKind = task.getType() == GenerationType.IMAGE
                    ? ConversationFileKind.IMAGE
                    : ConversationFileKind.VIDEO;
            PublishedMediaFile published = conversationFileService.createMediaFile(
                    task.getUserId(),
                    task.getConversationId(),
                    fileKind,
                    fileTitle(task),
                    asset.getId(),
                    asset.getMimeType(),
                    asset.getSizeBytes(),
                    asset.getChecksum());
            output.setFileId(published.file().getId());
            output.setFileVersionId(published.version().getId());
            output.setStatus(GenerationOutputStatus.PUBLISHED);
            output.setUpdatedAt(clock.instant());
            outputMapper.updateById(output);
            lastFileId = published.file().getId();
        }

        GenerationAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt != null) {
            attempt.setStatus(GenerationAttemptStatus.SUCCEEDED);
            attempt.setRequestId(receipt == null ? null : receipt.requestId());
            attempt.setUsagePayload(receipt == null ? null : receipt.usage());
            attempt.setCompletedAt(clock.instant());
            attemptMapper.updateById(attempt);
        }

        task.setStatus(GenerationStatus.PROCESSING);
        task.setProviderStatus("succeeded");
        task.setDeliveryStatus(GenerationDeliveryStatus.PUBLISHED);
        task.setOutputFileId(lastFileId);
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(clock.instant());
        taskMapper.updateById(task);
    }

    @Transactional
    public void settle(UUID taskId) {
        GenerationTask task = get(taskId);
        if (task.getBillingStatus() == GenerationBillingStatus.SETTLED) {
            return;
        }
        if (task.getStatus() != GenerationStatus.PROCESSING
                && task.getStatus() != GenerationStatus.PARTIAL_SUCCEEDED
                && task.getStatus() != GenerationStatus.SUCCEEDED) {
            throw new BusinessException(AgentErrorCode.GENERATION_STATE_CONFLICT);
        }
        creditService.settle(task.getUserId(), creditBusinessKey(taskId));
        Instant now = clock.instant();
        task.setBillingStatus(GenerationBillingStatus.SETTLED);
        task.setStatus(GenerationStatus.SUCCEEDED);
        if (task.getCancelStatus() == GenerationCancelStatus.REQUESTED) {
            task.setCancelStatus(GenerationCancelStatus.TOO_LATE);
        }
        task.setCompletedAt(now);
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
    }

    @Transactional
    public GenerationTask cancel(UUID userId, UUID taskId) {
        GenerationTask task = getForUser(userId, taskId);
        if (task.getStatus().terminal()) {
            return task;
        }
        Instant now = clock.instant();
        if (task.getStatus() == GenerationStatus.PENDING) {
            task.setStatus(GenerationStatus.CANCELLED);
            task.setCancelStatus(GenerationCancelStatus.CONFIRMED);
            task.setBillingStatus(GenerationBillingStatus.RELEASED);
            task.setCompletedAt(now);
            creditService.release(task.getUserId(), creditBusinessKey(taskId));
        } else {
            task.setCancelStatus(GenerationCancelStatus.REQUESTED);
            task.setCancelRequestedAt(now);
        }
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        return task;
    }

    /**
     * Repairs delivery without regenerating: only outputs whose asset already exists are
     * republished. If none are recoverable the task stays ARCHIVE_FAILED and the reservation is
     * kept for reconciliation.
     */
    @Transactional
    public GenerationTask retryArchive(UUID userId, UUID taskId) {
        GenerationTask task = getForUser(userId, taskId);
        if (task.getStatus() != GenerationStatus.ARCHIVE_FAILED
                && task.getStatus() != GenerationStatus.PROCESSING) {
            throw new BusinessException(AgentErrorCode.GENERATION_STATE_CONFLICT);
        }
        UUID lastFileId = task.getOutputFileId();
        boolean complete = true;
        for (GenerationOutput output : outputs(taskId)) {
            if (output.getStatus() == GenerationOutputStatus.PUBLISHED) {
                lastFileId = output.getFileId();
                continue;
            }
            if (output.getAssetId() == null) {
                complete = false;
                continue;
            }
            Asset asset = assetService.get(userId, output.getAssetId());
            PublishedMediaFile published = conversationFileService.createMediaFile(
                    userId,
                    task.getConversationId(),
                    task.getType() == GenerationType.IMAGE
                            ? ConversationFileKind.IMAGE
                            : ConversationFileKind.VIDEO,
                    fileTitle(task),
                    asset.getId(),
                    asset.getMimeType(),
                    asset.getSizeBytes(),
                    asset.getChecksum());
            output.setFileId(published.file().getId());
            output.setFileVersionId(published.version().getId());
            output.setStatus(GenerationOutputStatus.PUBLISHED);
            output.setUpdatedAt(clock.instant());
            outputMapper.updateById(output);
            lastFileId = published.file().getId();
        }
        if (!complete) {
            task.setErrorMessage("仍有产物缺少资产，不能只恢复归档");
            task.setUpdatedAt(clock.instant());
            taskMapper.updateById(task);
            return task;
        }
        Instant now = clock.instant();
        task.setOutputFileId(lastFileId);
        task.setStatus(GenerationStatus.PROCESSING);
        task.setDeliveryStatus(GenerationDeliveryStatus.PUBLISHED);
        task.setErrorMessage(null);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        if (task.getBillingStatus() == GenerationBillingStatus.RESERVED) {
            creditService.settle(task.getUserId(), creditBusinessKey(taskId));
            task.setBillingStatus(GenerationBillingStatus.SETTLED);
        }
        task.setStatus(GenerationStatus.SUCCEEDED);
        task.setCompletedAt(now);
        task.setStateVersion(task.getStateVersion() + 1);
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public void markFailed(UUID taskId, UUID attemptId, String errorMessage) {
        GenerationTask task = get(taskId);
        if (task.getStatus().terminal() || task.getStatus() == GenerationStatus.ARCHIVE_FAILED) {
            return;
        }
        Instant now = clock.instant();
        if (attemptId != null) {
            GenerationAttempt attempt = attemptMapper.selectById(attemptId);
            if (attempt != null) {
                attempt.setStatus(GenerationAttemptStatus.FAILED);
                attempt.setErrorMessage(errorMessage);
                attempt.setCompletedAt(now);
                attemptMapper.updateById(attempt);
            }
        }
        task.setStatus(GenerationStatus.FAILED);
        task.setDeliveryStatus(GenerationDeliveryStatus.FAILED);
        task.setBillingStatus(GenerationBillingStatus.RELEASED);
        task.setErrorMessage(truncate(errorMessage));
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(now);
        task.setCompletedAt(now);
        taskMapper.updateById(task);
        creditService.release(task.getUserId(), creditBusinessKey(taskId));
    }

    @Transactional
    public void markUnknown(UUID taskId, UUID attemptId, String errorMessage) {
        GenerationTask task = get(taskId);
        if (task.getStatus().terminal()) {
            return;
        }
        Instant now = clock.instant();
        if (attemptId != null) {
            GenerationAttempt attempt = attemptMapper.selectById(attemptId);
            if (attempt != null) {
                attempt.setStatus(GenerationAttemptStatus.UNKNOWN);
                attempt.setErrorMessage(errorMessage);
                attempt.setCompletedAt(now);
                attemptMapper.updateById(attempt);
            }
        }
        task.setStatus(GenerationStatus.UNKNOWN);
        task.setBillingStatus(GenerationBillingStatus.PENDING_RECONCILIATION);
        task.setErrorMessage(truncate(errorMessage));
        task.setStateVersion(task.getStateVersion() + 1);
        task.setUpdatedAt(now);
        task.setCompletedAt(now);
        taskMapper.updateById(task);
    }

    public List<WorkflowStartRequest> pendingStartRequests(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return startRequestMapper.selectList(
                Wrappers.<WorkflowStartRequest>lambdaQuery()
                        .eq(WorkflowStartRequest::getWorkflowType,
                                WorkflowStartType.MEDIA_GENERATION)
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
            markFailed(request.getBusinessId(), null, "生成任务启动失败: " + errorMessage);
        } else {
            request.setStatus(WorkflowStartStatus.PENDING);
        }
        startRequestMapper.updateById(request);
    }

    public static String workflowId(UUID taskId) {
        return "media-generation-" + taskId;
    }

    public static String creditBusinessKey(UUID taskId) {
        return "generation:" + taskId;
    }

    private WorkflowStartRequest findStartRequest(String workflowId) {
        return startRequestMapper.selectOne(
                Wrappers.<WorkflowStartRequest>lambdaQuery()
                        .eq(WorkflowStartRequest::getWorkflowId, workflowId));
    }

    private GenerationTask findByIdempotencyKey(UUID userId, String idempotencyKey) {
        return taskMapper.selectOne(
                Wrappers.<GenerationTask>lambdaQuery()
                        .eq(GenerationTask::getUserId, userId)
                        .eq(GenerationTask::getIdempotencyKey, idempotencyKey));
    }

    private List<ValidatedInput> validateInputs(
            UUID userId,
            UUID conversationId,
            GenerationType type,
            List<SubmitInput> inputs) {
        if (inputs.size() > MAX_INPUTS) {
            throw new BusinessException(
                    AgentErrorCode.GENERATION_INPUT_INVALID,
                    "单个任务最多引用 " + MAX_INPUTS + " 个输入素材");
        }
        List<ValidatedInput> result = new ArrayList<>(inputs.size());
        for (SubmitInput input : inputs) {
            if (input == null || input.assetId() == null) {
                throw new BusinessException(AgentErrorCode.GENERATION_INPUT_INVALID);
            }
            Asset asset = assetService.getForConversation(userId, conversationId, input.assetId());
            if (asset.getKind() != AssetKind.IMAGE) {
                throw new BusinessException(
                        AgentErrorCode.GENERATION_INPUT_INVALID, "当前只支持图片作为生成输入");
            }
            GenerationInputRole role = input.role() == null
                    ? (type == GenerationType.IMAGE
                            ? GenerationInputRole.REFERENCE
                            : GenerationInputRole.FIRST_FRAME)
                    : input.role();
            if (!roleAllowed(type, role)) {
                throw new BusinessException(
                        AgentErrorCode.GENERATION_INPUT_INVALID,
                        "输入角色 " + role + " 不适用于 " + type);
            }
            result.add(new ValidatedInput(input.assetId(), input.fileVersionId(), role));
        }
        return result;
    }

    private boolean roleAllowed(GenerationType type, GenerationInputRole role) {
        return switch (type) {
            case IMAGE -> role == GenerationInputRole.REFERENCE
                    || role == GenerationInputRole.INIT_IMAGE
                    || role == GenerationInputRole.MASK;
            case VIDEO -> role == GenerationInputRole.FIRST_FRAME
                    || role == GenerationInputRole.LAST_FRAME
                    || role == GenerationInputRole.REFERENCE;
        };
    }

    private UUID validateSourceTask(UUID userId, UUID sourceTaskId) {
        if (sourceTaskId == null) {
            return null;
        }
        getForUser(userId, sourceTaskId);
        return sourceTaskId;
    }

    private ModelCapabilityType capabilityOf(GenerationType type) {
        return switch (type) {
            case IMAGE -> ModelCapabilityType.IMAGE;
            case VIDEO -> ModelCapabilityType.VIDEO;
        };
    }

    private AssetKind assetKind(GenerationType type) {
        return type == GenerationType.IMAGE ? AssetKind.IMAGE : AssetKind.VIDEO;
    }

    private String normalizePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.isEmpty()) {
            throw new BusinessException(AgentErrorCode.GENERATION_PROMPT_EMPTY);
        }
        if (value.length() > MAX_PROMPT_LENGTH) {
            throw new BusinessException(
                    AgentErrorCode.GENERATION_INPUT_INVALID, "提示词超过最大长度");
        }
        return value;
    }

    private String normalizeParameters(String parameters) {
        String raw = parameters == null || parameters.isBlank() ? "{}" : parameters.trim();
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new BusinessException(
                        AgentErrorCode.GENERATION_INPUT_INVALID, "生成参数必须是 JSON 对象");
            }
            String normalized = objectMapper.writeValueAsString(node);
            if (normalized.length() > MAX_PARAMETERS_LENGTH) {
                throw new BusinessException(
                        AgentErrorCode.GENERATION_INPUT_INVALID, "生成参数超过最大长度");
            }
            return normalized;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    AgentErrorCode.GENERATION_INPUT_INVALID, "生成参数必须是合法 JSON");
        }
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String value = key.trim();
        if (value.length() > 200) {
            throw new BusinessException(
                    AgentErrorCode.GENERATION_INPUT_INVALID, "幂等键不能超过 200 个字符");
        }
        return value;
    }

    private String fingerprint(
            UUID conversationId,
            GenerationType type,
            UUID modelDefinitionId,
            String prompt,
            String parameters,
            List<ValidatedInput> inputs,
            UUID sourceTaskId) {
        StringBuilder value = new StringBuilder()
                .append(conversationId).append('|')
                .append(type).append('|')
                .append(modelDefinitionId).append('|')
                .append(prompt).append('|')
                .append(parameters).append('|')
                .append(sourceTaskId == null ? "" : sourceTaskId).append('|');
        for (ValidatedInput input : inputs) {
            value.append(input.assetId()).append(':')
                    .append(input.fileVersionId() == null ? "" : input.fileVersionId())
                    .append(':')
                    .append(input.role())
                    .append(',');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String fileTitle(GenerationTask task) {
        String prefix = task.getType() == GenerationType.IMAGE ? "生成的图片 " : "生成的视频 ";
        String suffix = task.getId().toString().substring(0, 8);
        return prefix + suffix;
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private record ValidatedInput(
            UUID assetId, UUID fileVersionId, GenerationInputRole role) {
    }
}
