package app.pagefault.visionforge.agent.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.conversation.Conversation;
import app.pagefault.visionforge.agent.conversation.ConversationMapper;
import app.pagefault.visionforge.agent.conversation.Message;
import app.pagefault.visionforge.agent.conversation.MessageMapper;
import app.pagefault.visionforge.agent.conversation.MessagePart;
import app.pagefault.visionforge.agent.conversation.MessagePartMapper;
import app.pagefault.visionforge.agent.conversation.MessagePartType;
import app.pagefault.visionforge.agent.conversation.MessageRole;
import app.pagefault.visionforge.agent.conversation.MessageStatus;
import app.pagefault.visionforge.agent.context.ContextCompactor;
import app.pagefault.visionforge.agent.context.ContextSummary;
import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.provider.ModelInputItem;
import app.pagefault.visionforge.agent.provider.ModelReasoning;
import app.pagefault.visionforge.agent.provider.ModelResponse;
import app.pagefault.visionforge.agent.provider.ModelToolCall;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentPrompt;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.skill.SkillRegistry;
import app.pagefault.visionforge.agent.tool.ToolInvocation;
import app.pagefault.visionforge.agent.tool.ToolInvocationMapper;
import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;
import app.pagefault.visionforge.agent.tool.ToolResult;
import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetKind;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.common.alert.AlertEvent;
import app.pagefault.visionforge.common.alert.AlertPublisher;
import app.pagefault.visionforge.common.alert.AlertSeverity;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class AgentRunService {

    private static final String SYSTEM_PROMPT = """
            你是 VisionForge（幻影工坊）的创作助手，帮助用户讨论创意、完善提示词、编写脚本和规划图片或视频。
            你可以调用会话文件工具读写剧本、分镜和提示词：需要查看或修改文件时先调用工具，不要臆造条目内容。
            剧本使用 Markdown（kind=SCRIPT）；分镜使用结构化 JSON（kind=STORYBOARD）；修改必须带 baseVersion。

            写作或修改剧本与分镜、制作短片或广告时，先加载 short-film skill，再调用创作工具。
            用户只要单张图片、已有首帧的单段视频或查询任务时，可直接调用相应工具。
            生成工具返回 ACCEPTED 和 taskId；只有 await_generation 返回的终态才可以描述为已交付。
            当前消息附带的素材会作为 input_image 提供，并在消息末尾列出 assetId；会话素材库中的素材用 list_assets 获取 assetId。inspect_asset 只返回元数据，不等于视觉理解。
            回答保持清晰、具体，并在需要时主动询问缺失的创作信息。
            """;
    private static final int ERROR_MAX_LENGTH = 2_000;
    private static final int IMAGE_CONTEXT_COST = 4_000;
    // A single space keeps the reasoning part non-empty: DeepSeek treats an empty text part as
    // missing and rejects the next tool-call message.
    private static final ModelInputItem.Reasoning EMPTY_REASONING =
            new ModelInputItem.Reasoning(null, " ", List.of());

    /**
     * Images are sent inline as base64 data URLs, so the request body grows by roughly a third.
     * These caps keep one model call well below the common gateway body limits.
     */
    private static final long MAX_INLINE_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_INLINE_IMAGE_TOTAL_BYTES = 30L * 1024 * 1024;

    private final AgentRunMapper agentRunMapper;
    private final ModelInvocationRecorder invocationRecorder;
    private final AgentRunTurnMapper agentRunTurnMapper;
    private final ToolInvocationMapper toolInvocationMapper;
    private final WorkflowStartRequestMapper workflowStartRequestMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final MessagePartMapper messagePartMapper;
    private final AssetService assetService;
    private final ModelDefinitionService modelDefinitionService;
    private final ContextCompactor contextCompactor;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private volatile AlertPublisher alertPublisher = AlertPublisher.NOOP;

    public void setAlertPublisher(AlertPublisher alertPublisher) {
        this.alertPublisher = alertPublisher == null ? AlertPublisher.NOOP : alertPublisher;
    }

    public AgentRun getForUser(UUID userId, UUID runId) {
        AgentRun run = runId == null ? null : agentRunMapper.selectById(runId);
        if (run == null || !userId.equals(run.getUserId())) {
            throw new BusinessException(AgentErrorCode.AGENT_RUN_NOT_FOUND);
        }
        return run;
    }

    /**
     * Newest-first runs owned by one user, optionally narrowed to a conversation and statuses.
     * Conversation ownership is validated by the caller so another user's id cannot be probed.
     */
    public List<AgentRun> list(
            UUID userId,
            UUID conversationId,
            Collection<AgentRunStatus> statuses,
            int limit) {
        List<AgentRunStatus> filtered = statuses == null
                ? List.of()
                : statuses.stream().filter(Objects::nonNull).distinct().toList();
        return agentRunMapper.selectList(
                Wrappers.<AgentRun>lambdaQuery()
                        .eq(AgentRun::getUserId, userId)
                        .eq(conversationId != null, AgentRun::getConversationId, conversationId)
                        .in(!filtered.isEmpty(), AgentRun::getStatus, filtered)
                        .orderByDesc(AgentRun::getCreatedAt)
                        .orderByDesc(AgentRun::getId)
                        .last("LIMIT " + safeLimit(limit)));
    }

    public AgentRun get(UUID runId) {
        AgentRun run = runId == null ? null : agentRunMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(AgentErrorCode.AGENT_RUN_NOT_FOUND);
        }
        return run;
    }

    @Transactional
    public AgentPrompt loadPrompt(UUID runId) {
        AgentRun run = get(runId);
        if (run.getStatus().terminal()) {
            throw new BusinessException(AgentErrorCode.AGENT_RUN_INVALID_STATE);
        }
        Conversation conversation = conversationMapper.selectById(run.getConversationId());
        if (conversation == null) {
            throw new BusinessException(AgentErrorCode.CONVERSATION_NOT_FOUND);
        }

        UUID preferredModelId = run.getModelDefinitionId() != null
                ? run.getModelDefinitionId()
                : conversation.getModelDefinitionId();
        ModelDefinition model = modelDefinitionService.resolveForRun(preferredModelId);

        Instant now = clock.instant();
        run.setStatus(AgentRunStatus.RUNNING);
        run.setModelDefinitionId(model.getId());
        run.setStartedAt(now);
        run.setErrorMessage(null);
        agentRunMapper.updateById(run);

        return new AgentPrompt(runId, model.getId(), model.getMaxInputTokens());
    }

    /**
     * Rebuilds the complete Responses {@code input}: conversation messages up to the current user
     * message, then every persisted turn of this run with its function call outputs. No
     * {@code previous_response_id} is involved, so the request is fully self-contained.
     */
    public AgentModelInput buildInput(UUID runId) {
        AgentRun run = get(runId);
        ModelDefinition model = modelDefinitionService.get(run.getModelDefinitionId());
        int budget = model.getMaxInputTokens();

        Message userMessage = run.getUserMessageId() == null
                ? null
                : messageMapper.selectById(run.getUserMessageId());
        final long limit = userMessage == null ? Long.MAX_VALUE : userMessage.getSequenceNo();

        int currentEpoch = contextCompactor.currentEpoch(run.getConversationId());
        List<ModelInputItem> runItems = runItems(runId, model, currentEpoch);
        String instructions = SYSTEM_PROMPT + skillRegistry.promptCatalog();
        int used = instructions.length();
        for (ModelInputItem item : runItems) {
            used += itemCost(item);
        }

        List<Message> history = messageMapper.selectList(
                        Wrappers.<Message>lambdaQuery()
                                .eq(Message::getConversationId, run.getConversationId())
                                .orderByAsc(Message::getSequenceNo))
                .stream()
                .filter(message -> message.getSequenceNo() <= limit)
                .filter(message -> message.getStatus() == MessageStatus.COMPLETED)
                .toList();

        ContextSummary summary = contextCompactor.ensureWithinBudget(
                run, model, history, used);
        long coveredToSeq = summary == null ? 0 : summary.getToSeq();
        if (summary != null) {
            used += ContextCompactor.inputContent(summary).length();
        }
        if (used > budget) {
            throw new BusinessException(AgentErrorCode.MESSAGE_TOO_LONG);
        }

        LinkedList<ModelInputItem> historyItems = new LinkedList<>();
        boolean currentUserMessageIncluded = false;
        for (int index = history.size() - 1; index >= 0; index--) {
            Message message = history.get(index);
            if (message.getSequenceNo() <= coveredToSeq) {
                break;
            }
            ModelInputItem item = message.getId().equals(run.getUserMessageId())
                    ? userMessageItem(run, message)
                    : new ModelInputItem.Message(role(message.getRole()), message.getContent());
            int cost = itemCost(item);
            if (used + cost > budget) {
                break;
            }
            historyItems.addFirst(item);
            used += cost;
            if (message.getId().equals(run.getUserMessageId())) {
                currentUserMessageIncluded = true;
            }
        }
        if (run.getUserMessageId() != null && !currentUserMessageIncluded) {
            throw new BusinessException(AgentErrorCode.MESSAGE_TOO_LONG);
        }

        List<ModelInputItem> input = new ArrayList<>(
                historyItems.size() + runItems.size() + 1);
        if (summary != null) {
            input.add(new ModelInputItem.Message(
                    "system", ContextCompactor.inputContent(summary)));
        }
        input.addAll(historyItems);
        input.addAll(runItems);
        return new AgentModelInput(instructions, input);
    }

    private List<ModelInputItem> runItems(UUID runId, ModelDefinition model, int currentEpoch) {
        List<ModelInputItem> items = new ArrayList<>();
        for (AgentRunTurn turn : listTurns(runId)) {
            List<ModelReasoning> reasoning = turn.getContextEpoch() == currentEpoch
                    ? parseReasoning(turn.getReasoning()).stream()
                            .filter(item -> shouldReplay(model.isReasoningEncrypted(), item))
                            .toList()
                    : List.of();
            List<ModelToolCall> calls = parseCalls(turn.getFunctionCalls());
            boolean hasText =
                    turn.getAssistantText() != null && !turn.getAssistantText().isBlank();

            for (ModelReasoning item : reasoning) {
                items.add(reasoningItem(item));
            }
            if (hasText) {
                items.add(new ModelInputItem.Message("assistant", turn.getAssistantText()));
            }
            ModelReasoning repeated = reasoning.isEmpty() ? null : reasoning.get(0);
            for (int index = 0; index < calls.size(); index++) {
                // Plaintext reasoning is merged into the adjacent assistant message, and every
                // function call is its own assistant message. Replay the same reasoning before
                // each call so every tool-call message carries reasoning_text.
                if (!model.isReasoningEncrypted()
                        && (hasText || index > 0 || reasoning.isEmpty())) {
                    items.add(repeated == null ? EMPTY_REASONING : reasoningItem(repeated));
                }
                ModelToolCall call = calls.get(index);
                items.add(new ModelInputItem.FunctionCall(
                        call.callId(), call.name(), call.arguments()));
                ToolInvocation invocation = findInvocation(runId, call.callId());
                if (invocation != null) {
                    items.add(new ModelInputItem.FunctionCallOutput(
                            call.callId(), toolOutput(invocation)));
                }
            }
        }
        return items;
    }

    private ModelInputItem.Reasoning reasoningItem(ModelReasoning reasoning) {
        return new ModelInputItem.Reasoning(
                reasoning.encryptedContent(), reasoning.text(), reasoning.summary());
    }

    private ModelInputItem userMessageItem(AgentRun run, Message message) {
        List<MessagePart> parts = messagePartMapper.selectList(
                Wrappers.<MessagePart>lambdaQuery()
                        .eq(MessagePart::getMessageId, message.getId())
                        .eq(MessagePart::getPartType, MessagePartType.ASSET_REFERENCE)
                        .orderByAsc(MessagePart::getOrdinal));
        if (parts.isEmpty()) {
            return new ModelInputItem.Message(role(message.getRole()), message.getContent());
        }
        List<ModelInputItem.ImageInput> images = new ArrayList<>(parts.size());
        StringBuilder manifest = new StringBuilder();
        long totalBytes = 0;
        for (MessagePart part : parts) {
            if (part.getAssetId() == null) {
                continue;
            }
            Asset asset = assetService.get(run.getUserId(), part.getAssetId());
            // Every attachment is listed so the model can reference it by assetId even when it
            // is not an input_image part.
            manifest.append("- assetId=").append(asset.getId())
                    .append(" kind=").append(asset.getKind())
                    .append(" mime=").append(asset.getMimeType());
            if (asset.getWidth() != null && asset.getHeight() != null) {
                manifest.append(" size=").append(asset.getWidth())
                        .append('x').append(asset.getHeight());
            }
            manifest.append('\n');

            if (asset.getKind() != AssetKind.IMAGE) {
                // input_image only accepts images; other attachments stay metadata-only.
                continue;
            }
            if (asset.getSizeBytes() > MAX_INLINE_IMAGE_BYTES) {
                throw new BusinessException(
                        AssetErrorCode.ASSET_SIZE_TOO_LARGE,
                        "单张图片不能超过 10 MB，请压缩后重试");
            }
            if (totalBytes + asset.getSizeBytes() > MAX_INLINE_IMAGE_TOTAL_BYTES) {
                throw new BusinessException(
                        AssetErrorCode.ASSET_SIZE_TOO_LARGE,
                        "单条消息附带的图片总大小不能超过 30 MB，请减少图片数量或压缩后重试");
            }
            byte[] content = assetService.readBytes(
                    run.getUserId(), part.getAssetId(), MAX_INLINE_IMAGE_BYTES);
            totalBytes += content.length;
            images.add(new ModelInputItem.ImageInput(
                    dataUrl(asset, content), "auto"));
        }
        String text = message.getContent() == null ? "" : message.getContent();
        if (!manifest.isEmpty()) {
            text = (text.isBlank() ? "" : text + "\n\n")
                    + "[本次消息附件]\n" + manifest
                    + "使用 generate_image / generate_video 的 inputs[].assetId 引用这些素材。";
        }
        return new ModelInputItem.UserMessage(text, images);
    }

    private String dataUrl(Asset asset, byte[] content) {
        return "data:" + AssetKind.normalizeMimeType(asset.getMimeType())
                + ";base64," + Base64.getEncoder().encodeToString(content);
    }

    private int itemCost(ModelInputItem item) {
        return switch (item) {
            case ModelInputItem.Message message -> length(message.content());
            case ModelInputItem.UserMessage message -> length(message.text())
                    + message.images().size() * IMAGE_CONTEXT_COST;
            case ModelInputItem.FunctionCall call -> length(call.arguments());
            case ModelInputItem.FunctionCallOutput output -> length(output.output());
            case ModelInputItem.Reasoning reasoning -> length(reasoning.encryptedContent())
                    + length(reasoning.text())
                    + reasoning.summary().stream().mapToInt(this::length).sum();
        };
    }

    /**
     * Encrypted models can only replay the opaque carrier; plaintext models replay their readable
     * chain of thought because the provider merges it back into the adjacent assistant message.
     */
    private boolean shouldReplay(boolean reasoningEncrypted, ModelReasoning reasoning) {
        boolean encrypted = reasoning.encryptedContent() != null
                && !reasoning.encryptedContent().isBlank();
        if (reasoningEncrypted) {
            return encrypted;
        }
        return encrypted
                || (reasoning.text() != null && !reasoning.text().isBlank())
                || !reasoning.summary().isEmpty();
    }

    public AgentRunTurn findTurn(UUID runId, int turnNo) {
        return agentRunTurnMapper.selectOne(
                Wrappers.<AgentRunTurn>lambdaQuery()
                        .eq(AgentRunTurn::getAgentRunId, runId)
                        .eq(AgentRunTurn::getTurnNo, turnNo));
    }

    public List<ModelToolCall> toolCalls(UUID runId, int turnNo) {
        AgentRunTurn turn = findTurn(runId, turnNo);
        return turn == null ? List.of() : parseCalls(turn.getFunctionCalls());
    }

    @Transactional
    public AgentRunTurn recordTurn(UUID runId, int turnNo, ModelResponse response) {
        AgentRunTurn turn = new AgentRunTurn();
        turn.setId(idGenerator.nextId());
        turn.setAgentRunId(runId);
        turn.setTurnNo(turnNo);
        turn.setAssistantText(response.text());
        turn.setFunctionCalls(writeCalls(response.toolCalls()));
        turn.setReasoning(writeReasoning(response.reasoning()));
        turn.setContextEpoch(
                contextCompactor.currentEpoch(get(runId).getConversationId()));
        turn.setResponseId(response.responseId());
        turn.setStatus("incomplete".equalsIgnoreCase(response.finishReason())
                ? AgentTurnStatus.INCOMPLETE
                : AgentTurnStatus.COMPLETED);
        turn.setCreatedAt(clock.instant());
        agentRunTurnMapper.insert(turn);
        return turn;
    }

    public ToolCallLookup findToolCallWithTurn(UUID runId, String toolCallId) {
        for (AgentRunTurn turn : listTurns(runId)) {
            for (ModelToolCall call : parseCalls(turn.getFunctionCalls())) {
                if (call.callId().equals(toolCallId)) {
                    return new ToolCallLookup(turn.getTurnNo(), call);
                }
            }
        }
        return null;
    }

    public ModelToolCall findToolCall(UUID runId, String toolCallId) {
        ToolCallLookup lookup = findToolCallWithTurn(runId, toolCallId);
        return lookup == null ? null : lookup.call();
    }

    public ToolInvocationSummary summarizeTool(
            ModelToolCall call,
            ToolInvocationStatus status,
            String resultJson,
            String errorMessage) {
        ToolDisplayFormatter.Display display = new ToolDisplayFormatter(objectMapper)
                .format(call.name(), call.arguments(), resultJson);
        return new ToolInvocationSummary(
                call.callId(),
                call.name(),
                display.action(),
                display.target(),
                status,
                display.fileId(),
                display.taskId(),
                errorMessage,
                null,
                null);
    }

    public record ToolCallLookup(int turnNo, ModelToolCall call) {
    }

    public ToolInvocation findInvocation(UUID runId, String toolCallId) {
        return toolInvocationMapper.selectOne(
                Wrappers.<ToolInvocation>lambdaQuery()
                        .eq(ToolInvocation::getAgentRunId, runId)
                        .eq(ToolInvocation::getToolCallId, toolCallId));
    }

    public ToolExecutionContext toolContext(UUID runId, String toolCallId) {
        AgentRun run = get(runId);
        return new ToolExecutionContext(
                run.getUserId(),
                run.getConversationId(),
                runId,
                toolCallId);
    }

    public void recordInvocation(
            UUID runId,
            UUID modelDefinitionId,
            ModelInvocationPurpose purpose,
            ModelInvocationStatus status,
            ModelResponse result,
            String errorMessage) {
        invocationRecorder.record(
                runId, modelDefinitionId, purpose, status, result, errorMessage);
    }

    @Transactional
    public UUID complete(UUID runId, int finalTurnNo) {
        AgentRun run = get(runId);
        if (run.getStatus() == AgentRunStatus.COMPLETED || run.getAssistantMessageId() != null) {
            return run.getAssistantMessageId();
        }
        if (run.getStatus().terminal()) {
            throw new BusinessException(AgentErrorCode.AGENT_RUN_INVALID_STATE);
        }
        AgentRunTurn turn = findTurn(runId, finalTurnNo);
        if (turn == null || turn.getAssistantText() == null || turn.getAssistantText().isBlank()) {
            throw new BusinessException(AgentErrorCode.AGENT_RUN_INVALID_STATE);
        }
        // Same lock as startTurn: message sequence allocation must not race a new user message.
        conversationMapper.selectForUpdate(run.getConversationId());
        Conversation conversation = conversationMapper.selectById(run.getConversationId());
        if (conversation == null) {
            throw new BusinessException(AgentErrorCode.CONVERSATION_NOT_FOUND);
        }
        Instant now = clock.instant();

        Message assistantMessage = new Message();
        assistantMessage.setId(idGenerator.nextId());
        assistantMessage.setConversationId(run.getConversationId());
        assistantMessage.setSenderUserId(run.getUserId());
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setContent(turn.getAssistantText());
        assistantMessage.setSequenceNo(nextSequence(run.getConversationId()));
        assistantMessage.setStatus(MessageStatus.COMPLETED);
        assistantMessage.setCreatedAt(now);
        messageMapper.insert(assistantMessage);

        run.setAssistantMessageId(assistantMessage.getId());
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setCompletedAt(now);
        run.setErrorMessage(null);
        agentRunMapper.updateById(run);

        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return assistantMessage.getId();
    }

    @Transactional
    public boolean fail(UUID runId, String errorMessage) {
        AgentRun run = get(runId);
        if (run.getStatus() == AgentRunStatus.COMPLETED
                || run.getStatus() == AgentRunStatus.CANCELLED
                || (run.getStatus() == AgentRunStatus.FAILED && run.getErrorMessage() != null)) {
            return false;
        }
        // Same lock as startTurn/complete: the failed reply still consumes a message sequence.
        conversationMapper.selectForUpdate(run.getConversationId());
        AgentRun current = agentRunMapper.selectById(runId);
        if (current == null
                || current.getStatus() == AgentRunStatus.COMPLETED
                || current.getStatus() == AgentRunStatus.CANCELLED
                || (current.getStatus() == AgentRunStatus.FAILED
                        && current.getErrorMessage() != null)) {
            return false;
        }
        Instant now = clock.instant();
        String safeError = truncate(errorMessage);
        current.setStatus(AgentRunStatus.FAILED);
        current.setErrorMessage(safeError);
        current.setCompletedAt(now);
        persistTerminalMessage(current, now, safeError == null || safeError.isBlank()
                ? "本次回复生成失败，请稍后重试。"
                : safeError);
        agentRunMapper.updateById(current);
        alertPublisher.publish(AlertEvent.of(
                AlertEvent.EVENT_MODEL_PROVIDER_ERROR,
                AlertSeverity.WARNING,
                "agent-run",
                "AgentRun 执行失败",
                errorMessage == null ? "未知执行异常" : safeError,
                runId.toString()));
        return true;
    }

    @Transactional
    public AgentRun cancel(UUID userId, UUID runId) {
        AgentRun run = getForUser(userId, runId);
        if (run.getStatus().terminal()) {
            return run;
        }
        conversationMapper.selectForUpdate(run.getConversationId());
        AgentRun current = agentRunMapper.selectById(runId);
        if (current == null || current.getStatus().terminal()) {
            return current == null ? run : current;
        }
        Instant now = clock.instant();
        current.setStatus(AgentRunStatus.CANCELLED);
        current.setErrorMessage("用户已取消生成");
        current.setCompletedAt(now);
        persistTerminalMessage(current, now, "用户已取消本次回复。");
        agentRunMapper.updateById(current);

        WorkflowStartRequest startRequest = findStartRequest(current.getWorkflowId());
        if (startRequest != null && startRequest.getStatus() == WorkflowStartStatus.PENDING) {
            startRequest.setStatus(WorkflowStartStatus.FAILED);
            startRequest.setLastError("用户已取消生成");
            startRequest.setUpdatedAt(now);
            workflowStartRequestMapper.updateById(startRequest);
        }
        return current;
    }

    /**
     * Persists an assistant bubble for a run that ended without a reply, so failures and
     * cancellations stay visible after a refresh instead of leaving only the user message.
     */
    private void persistTerminalMessage(AgentRun run, Instant now, String content) {
        if (run.getAssistantMessageId() != null) {
            return;
        }
        Conversation conversation = conversationMapper.selectById(run.getConversationId());
        if (conversation == null) {
            return;
        }
        Message message = new Message();
        message.setId(idGenerator.nextId());
        message.setConversationId(run.getConversationId());
        message.setSenderUserId(run.getUserId());
        message.setRole(MessageRole.ASSISTANT);
        message.setContent(content);
        message.setSequenceNo(nextSequence(run.getConversationId()));
        message.setStatus(MessageStatus.FAILED);
        message.setCreatedAt(now);
        messageMapper.insert(message);

        run.setAssistantMessageId(message.getId());
        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
    }

    public List<WorkflowStartRequest> pendingStartRequests(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return workflowStartRequestMapper.selectList(
                Wrappers.<WorkflowStartRequest>lambdaQuery()
                        .eq(WorkflowStartRequest::getWorkflowType, WorkflowStartType.AGENT_RUN)
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
        workflowStartRequestMapper.updateById(request);
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
        workflowStartRequestMapper.updateById(request);
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
            fail(request.getAgentRunId(), "AgentRun 启动失败: " + errorMessage);
        } else {
            request.setStatus(WorkflowStartStatus.PENDING);
        }
        workflowStartRequestMapper.updateById(request);
    }

    private List<AgentRunTurn> listTurns(UUID runId) {
        return agentRunTurnMapper.selectList(
                Wrappers.<AgentRunTurn>lambdaQuery()
                        .eq(AgentRunTurn::getAgentRunId, runId)
                        .orderByAsc(AgentRunTurn::getTurnNo));
    }

    private List<ModelToolCall> parseCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            ModelToolCall[] calls = objectMapper.readValue(json, ModelToolCall[].class);
            return List.of(calls);
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    AgentErrorCode.AGENT_RUN_INVALID_STATE, "持久化的工具调用记录无法解析");
        }
    }

    private List<ModelReasoning> parseReasoning(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            ModelReasoning[] reasoning = objectMapper.readValue(json, ModelReasoning[].class);
            return List.of(reasoning);
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    AgentErrorCode.AGENT_RUN_INVALID_STATE, "持久化的推理项无法解析");
        }
    }

    private String writeReasoning(List<ModelReasoning> reasoning) {
        if (reasoning == null || reasoning.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(reasoning);
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    AgentErrorCode.AGENT_RUN_INVALID_STATE, "推理项无法序列化");
        }
    }

    private String writeCalls(List<ModelToolCall> calls) {
        if (calls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(calls);
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    AgentErrorCode.AGENT_RUN_INVALID_STATE, "工具调用记录无法序列化");
        }
    }

    private String toolOutput(ToolInvocation invocation) {
        if (invocation.getStatus() == ToolInvocationStatus.SUCCEEDED) {
            return invocation.getResult() == null ? "{}" : invocation.getResult();
        }
        return ToolResult.failure(invocation.getErrorCode(), invocation.getErrorMessage())
                .modelContent();
    }

    private String role(MessageRole role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private WorkflowStartRequest findStartRequest(String workflowId) {
        return workflowStartRequestMapper.selectOne(
                Wrappers.<WorkflowStartRequest>lambdaQuery()
                        .eq(WorkflowStartRequest::getWorkflowId, workflowId));
    }

    private long nextSequence(UUID conversationId) {
        Message last = messageMapper.selectOne(
                Wrappers.<Message>lambdaQuery()
                        .eq(Message::getConversationId, conversationId)
                        .orderByDesc(Message::getSequenceNo)
                        .last("LIMIT 1"));
        return last == null ? 1 : last.getSequenceNo() + 1;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MAX_LENGTH ? value : value.substring(0, ERROR_MAX_LENGTH);
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }
}
