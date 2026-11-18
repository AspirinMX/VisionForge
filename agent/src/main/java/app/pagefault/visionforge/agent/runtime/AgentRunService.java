package app.pagefault.visionforge.agent.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
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
import app.pagefault.visionforge.agent.model.ReasoningReplayPolicy;
import app.pagefault.visionforge.agent.provider.ModelInputItem;
import app.pagefault.visionforge.agent.provider.ModelReasoning;
import app.pagefault.visionforge.agent.provider.ModelResponse;
import app.pagefault.visionforge.agent.provider.ModelToolCall;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentPrompt;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.tool.ToolInvocation;
import app.pagefault.visionforge.agent.tool.ToolInvocationMapper;
import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;
import app.pagefault.visionforge.agent.tool.ToolResult;
import app.pagefault.visionforge.asset.media.AssetDownloadTicket;
import app.pagefault.visionforge.asset.media.AssetService;
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

            用户要求“生成一条短片/广告”时，默认执行五步：
            1. 写剧本：从一句话提取主体、事件、目标时长，补全场景、角色和可见动作，保存 SCRIPT。
            2. 写分镜：一次写出整组镜头，保存 STORYBOARD；每镜必须分别填写 firstFramePrompt（第一帧看见什么）
               和 videoPrompt（随后发生什么、如何运镜），两者不能合并。调用 validate_storyboard 校验。
            3. 逐镜首帧：对每个镜头调用 generate_image，传入 shotId、storyboardFileId 和 firstFramePrompt；
               随后调用 await_generation 等待图片交付。把结果中的 assetId 记录到该镜头的首帧输入。
            4. 逐镜视频：对每个镜头调用 generate_video，传入 shotId、storyboardFileId、该镜 firstFramePrompt 对应的
               首帧资产和 videoPrompt；随后调用 await_generation 等待视频交付。一次调用只生成一个候选。
               重做某一镜时只为该镜创建新任务，不影响其他镜头。
            5. 成片：所有镜头都有选中视频后调用 compose_video，传 storyboardFileId。成片任务在后台继续，
               不要声称成片已经生成；用户询问时调用 get_composition。

            用户只要一张图片、一个已有首帧的片段，或只修改某一步时，可以直接调用对应工具，不必补全五步。
            生成工具返回 ACCEPTED 和 taskId；只有 await_generation 返回的终态才可以描述为已交付。
            当前消息附带的图片会作为 input_image 提供给你；inspect_asset 只返回元数据，不等于视觉理解。
            回答保持清晰、具体，并在需要时主动询问缺失的创作信息。
            """;
    private static final int ERROR_MAX_LENGTH = 2_000;
    private static final int IMAGE_CONTEXT_COST = 4_000;

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
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public AgentRun getForUser(UUID userId, UUID runId) {
        AgentRun run = runId == null ? null : agentRunMapper.selectById(runId);
        if (run == null || !userId.equals(run.getUserId())) {
            throw new BusinessException(AgentErrorCode.AGENT_RUN_NOT_FOUND);
        }
        return run;
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
        if (run.getStatus() == AgentRunStatus.COMPLETED || run.getStatus() == AgentRunStatus.FAILED) {
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
        int used = SYSTEM_PROMPT.length();
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
        return new AgentModelInput(SYSTEM_PROMPT, input);
    }

    private List<ModelInputItem> runItems(UUID runId, ModelDefinition model, int currentEpoch) {
        List<ModelInputItem> items = new ArrayList<>();
        for (AgentRunTurn turn : listTurns(runId)) {
            if (turn.getContextEpoch() == currentEpoch) {
                for (ModelReasoning reasoning : parseReasoning(turn.getReasoning())) {
                    if (shouldReplay(model.getReasoningReplay(), reasoning)) {
                        items.add(new ModelInputItem.Reasoning(
                                reasoning.encryptedContent(),
                                reasoning.text(),
                                reasoning.summary()));
                    }
                }
            }
            if (turn.getAssistantText() != null && !turn.getAssistantText().isBlank()) {
                items.add(new ModelInputItem.Message("assistant", turn.getAssistantText()));
            }
            for (ModelToolCall call : parseCalls(turn.getFunctionCalls())) {
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
        for (MessagePart part : parts) {
            if (part.getAssetId() == null) {
                continue;
            }
            AssetDownloadTicket ticket =
                    assetService.createDownloadUrl(run.getUserId(), part.getAssetId());
            images.add(new ModelInputItem.ImageInput(
                    ticket.downloadUrl().toString(), "auto"));
        }
        return new ModelInputItem.UserMessage(message.getContent(), images);
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

    private boolean shouldReplay(ReasoningReplayPolicy policy, ModelReasoning reasoning) {
        if (policy == null || policy == ReasoningReplayPolicy.NONE) {
            return false;
        }
        boolean encrypted = reasoning.encryptedContent() != null
                && !reasoning.encryptedContent().isBlank();
        if (policy == ReasoningReplayPolicy.ENCRYPTED) {
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

    public ModelToolCall findToolCall(UUID runId, String toolCallId) {
        for (AgentRunTurn turn : listTurns(runId)) {
            for (ModelToolCall call : parseCalls(turn.getFunctionCalls())) {
                if (call.callId().equals(toolCallId)) {
                    return call;
                }
            }
        }
        return null;
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
        if (run.getStatus() == AgentRunStatus.FAILED) {
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
                || (run.getStatus() == AgentRunStatus.FAILED && run.getErrorMessage() != null)) {
            return false;
        }
        run.setStatus(AgentRunStatus.FAILED);
        run.setErrorMessage(truncate(errorMessage));
        run.setCompletedAt(clock.instant());
        agentRunMapper.updateById(run);
        return true;
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
}
