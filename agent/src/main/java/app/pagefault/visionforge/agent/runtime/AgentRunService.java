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
import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.provider.ChatCompletionResult;
import app.pagefault.visionforge.agent.provider.ChatTurn;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentPrompt;
import app.pagefault.visionforge.agent.temporal.agentrun.ModelReply;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AgentRunService {

    private static final String SYSTEM_PROMPT = """
            你是 VisionForge（幻影工坊）的创作助手，帮助用户讨论创意、完善提示词、编写剧本和规划图片或视频。
            当前版本只支持文本对话，不能调用工具，也不能生成图片或视频；不要声称已经生成媒体。
            回答保持清晰、具体，并在需要时主动询问缺失的创作信息。
            """;
    private static final int ERROR_MAX_LENGTH = 2_000;

    private final AgentRunMapper agentRunMapper;
    private final ModelInvocationMapper modelInvocationMapper;
    private final WorkflowStartRequestMapper workflowStartRequestMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ModelDefinitionService modelDefinitionService;
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

        List<Message> messages = messageMapper.selectList(
                Wrappers.<Message>lambdaQuery()
                        .eq(Message::getConversationId, run.getConversationId())
                        .orderByAsc(Message::getSequenceNo));
        List<ChatTurn> turns = buildTurns(messages, model.getMaxInputTokens());

        Instant now = clock.instant();
        run.setStatus(AgentRunStatus.RUNNING);
        run.setModelDefinitionId(model.getId());
        run.setStartedAt(now);
        run.setErrorMessage(null);
        agentRunMapper.updateById(run);

        return new AgentPrompt(runId, model.getId(), model.getMaxInputTokens(), turns);
    }

    public void recordInvocation(
            UUID runId,
            UUID modelDefinitionId,
            ModelInvocationStatus status,
            ChatCompletionResult result,
            String errorMessage) {
        ModelInvocation invocation = new ModelInvocation();
        invocation.setId(idGenerator.nextId());
        invocation.setAgentRunId(runId);
        invocation.setModelDefinitionId(modelDefinitionId);
        invocation.setStatus(status);
        invocation.setInputTokens(result == null ? null : result.inputTokens());
        invocation.setOutputTokens(result == null ? null : result.outputTokens());
        invocation.setLatencyMs(result == null ? null : result.latencyMs());
        invocation.setFinishReason(result == null ? null : result.finishReason());
        invocation.setErrorMessage(errorMessage);
        invocation.setCreatedAt(clock.instant());
        modelInvocationMapper.insert(invocation);
    }

    @Transactional
    public UUID complete(UUID runId, ModelReply reply) {
        AgentRun run = get(runId);
        if (run.getStatus() == AgentRunStatus.COMPLETED || run.getAssistantMessageId() != null) {
            return run.getAssistantMessageId();
        }
        if (run.getStatus() == AgentRunStatus.FAILED) {
            throw new BusinessException(AgentErrorCode.AGENT_RUN_INVALID_STATE);
        }
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
        assistantMessage.setContent(reply.content());
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

    private List<ChatTurn> buildTurns(List<Message> messages, int maxInputTokens) {
        int used = SYSTEM_PROMPT.length();
        if (used > maxInputTokens) {
            throw new BusinessException(AgentErrorCode.MESSAGE_TOO_LONG);
        }
        LinkedList<ChatTurn> selected = new LinkedList<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message.getStatus() != MessageStatus.COMPLETED) {
                continue;
            }
            String content = message.getContent();
            int cost = content.length();
            if (used + cost > maxInputTokens) {
                if (selected.isEmpty()) {
                    throw new BusinessException(AgentErrorCode.MESSAGE_TOO_LONG);
                }
                break;
            }
            selected.addFirst(new ChatTurn(
                    message.getRole().name().toLowerCase(Locale.ROOT), content));
            used += cost;
        }
        List<ChatTurn> turns = new ArrayList<>(selected.size() + 1);
        turns.add(new ChatTurn("system", SYSTEM_PROMPT));
        turns.addAll(selected);
        return turns;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MAX_LENGTH ? value : value.substring(0, ERROR_MAX_LENGTH);
    }
}
