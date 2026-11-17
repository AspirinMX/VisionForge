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
import app.pagefault.visionforge.agent.conversation.MessageRole;
import app.pagefault.visionforge.agent.conversation.MessageStatus;
import app.pagefault.visionforge.agent.context.ContextCompactor;
import app.pagefault.visionforge.agent.context.ContextSummary;
import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.provider.ModelInputItem;
import app.pagefault.visionforge.agent.provider.ModelResponse;
import app.pagefault.visionforge.agent.provider.ModelToolCall;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentPrompt;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.tool.ToolInvocation;
import app.pagefault.visionforge.agent.tool.ToolInvocationMapper;
import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;
import app.pagefault.visionforge.agent.tool.ToolResult;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class AgentRunService {

    private static final String SYSTEM_PROMPT = """
            你是 VisionForge（幻影工坊）的创作助手，帮助用户讨论创意、完善提示词、编写脚本和规划图片或视频。
            你可以调用工作空间存储工具读写脚本、分镜和提示词条目：需要查看或修改文件时先调用工具，不要臆造条目内容。
            当前版本还不能生成图片或视频，不要声称已经生成媒体。
            回答保持清晰、具体，并在需要时主动询问缺失的创作信息。
            """;
    private static final int ERROR_MAX_LENGTH = 2_000;

    private final AgentRunMapper agentRunMapper;
    private final ModelInvocationRecorder invocationRecorder;
    private final AgentRunTurnMapper agentRunTurnMapper;
    private final ToolInvocationMapper toolInvocationMapper;
    private final WorkflowStartRequestMapper workflowStartRequestMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ModelDefinitionService modelDefinitionService;
    private final ContextCompactor contextCompactor;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public AgentRun getForWorkspace(UUID workspaceId, UUID runId) {
        AgentRun run = runId == null ? null : agentRunMapper.selectById(runId);
        if (run == null || !workspaceId.equals(run.getWorkspaceId())) {
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

        List<ModelInputItem> runItems = runItems(runId);
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
            String content = message.getContent();
            int cost = length(content);
            if (used + cost > budget) {
                break;
            }
            historyItems.addFirst(new ModelInputItem.Message(role(message.getRole()), content));
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

    private List<ModelInputItem> runItems(UUID runId) {
        List<ModelInputItem> items = new ArrayList<>();
        for (AgentRunTurn turn : listTurns(runId)) {
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

    private int itemCost(ModelInputItem item) {
        return switch (item) {
            case ModelInputItem.Message message -> length(message.content());
            case ModelInputItem.FunctionCall call -> length(call.arguments());
            case ModelInputItem.FunctionCallOutput output -> length(output.output());
        };
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
                run.getWorkspaceId(),
                run.getConversationId(),
                run.getUserId(),
                runId,
                toolCallId,
                run.isPlatformAdmin());
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
        assistantMessage.setWorkspaceId(run.getWorkspaceId());
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
