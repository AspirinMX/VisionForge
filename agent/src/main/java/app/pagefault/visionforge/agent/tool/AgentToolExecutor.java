package app.pagefault.visionforge.agent.tool;

import java.time.Clock;

import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes registered tools and records each model tool call exactly once per AgentRun.
 *
 * <p>Tool failures are returned to the model as structured results. Only an invocation that is
 * still RUNNING is treated as a conflict; completed calls are replayed from the durable record.
 */
@Slf4j
@RequiredArgsConstructor
public class AgentToolExecutor {

    private static final String EMPTY_ARGUMENTS = "{}";

    private final ToolRegistry toolRegistry;
    private final ToolInvocationMapper invocationMapper;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public ToolResult execute(
            ToolExecutionContext context, String toolName, String argumentsJson) {
        requireContext(context);
        AgentTool tool;
        try {
            tool = toolRegistry.require(toolName);
        } catch (BusinessException exception) {
            return ToolResult.failure(
                    exception.getErrorCode().code(), exception.getMessage());
        }

        String rawArguments = argumentsJson == null || argumentsJson.isBlank()
                ? EMPTY_ARGUMENTS
                : argumentsJson;
        JsonNode arguments = parseArguments(rawArguments);
        ToolInvocation existing = find(context);
        if (existing != null) {
            return replay(existing);
        }

        ToolInvocation invocation = new ToolInvocation();
        invocation.setId(idGenerator.nextId());
        invocation.setAgentRunId(context.agentRunId());
        invocation.setConversationId(context.conversationId());
        invocation.setToolCallId(context.toolCallId());
        invocation.setToolName(toolName);
        invocation.setArguments(rawArguments);
        invocation.setStatus(ToolInvocationStatus.RUNNING);
        invocation.setCreatedAt(clock.instant());
        try {
            invocationMapper.insert(invocation);
        } catch (DuplicateKeyException exception) {
            ToolInvocation raced = find(context);
            if (raced == null) {
                throw exception;
            }
            return replay(raced);
        }

        ToolResult result;
        try {
            result = tool.execute(context, arguments);
            if (result == null) {
                result = ToolResult.failure(
                        AgentErrorCode.TOOL_EXECUTION_FAILED.code(),
                        "工具没有返回结果");
            }
        } catch (BusinessException exception) {
            result = ToolResult.failure(
                    exception.getErrorCode().code(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("Tool execution failed: name={}, callId={}",
                    toolName, context.toolCallId(), exception);
            result = ToolResult.failure(
                    AgentErrorCode.TOOL_EXECUTION_FAILED.code(),
                    AgentErrorCode.TOOL_EXECUTION_FAILED.message());
        }
        if (result.waiting()) {
            invocation.setStatus(ToolInvocationStatus.WAITING);
            invocation.setResult(result.content());
            invocation.setErrorCode(null);
            invocation.setErrorMessage(null);
            invocation.setCompletedAt(null);
            invocationMapper.updateById(invocation);
            return result;
        }
        complete(invocation, result);
        return result;
    }

    /**
     * Completes a waiting invocation with the final durable result. The same tool call is not
     * executed again; the model receives the waited-on payload as this call's output.
     */
    public void completeWaiting(ToolExecutionContext context, String content) {
        ToolInvocation invocation = find(context);
        if (invocation == null) {
            throw new BusinessException(AgentErrorCode.TOOL_NOT_FOUND);
        }
        invocation.setStatus(ToolInvocationStatus.SUCCEEDED);
        invocation.setResult(content);
        invocation.setErrorCode(null);
        invocation.setErrorMessage(null);
        invocation.setCompletedAt(clock.instant());
        invocationMapper.updateById(invocation);
    }

    private ToolResult replay(ToolInvocation invocation) {
        if (invocation.getStatus() == ToolInvocationStatus.RUNNING) {
            throw new BusinessException(AgentErrorCode.TOOL_INVOCATION_IN_PROGRESS);
        }
        if (invocation.getStatus() == ToolInvocationStatus.WAITING) {
            return ToolResult.waiting(waitTaskId(invocation.getResult()));
        }
        if (invocation.getStatus() == ToolInvocationStatus.SUCCEEDED) {
            return ToolResult.success(invocation.getResult());
        }
        return ToolResult.failure(invocation.getErrorCode(), invocation.getErrorMessage());
    }

    private String waitTaskId(String content) {
        try {
            JsonNode node = objectMapper.readTree(content == null ? "{}" : content);
            return node.path("taskId").asText();
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    AgentErrorCode.AGENT_RUN_INVALID_STATE, "等待中的工具调用记录无法解析");
        }
    }

    private void complete(ToolInvocation invocation, ToolResult result) {
        invocation.setStatus(result.success()
                ? ToolInvocationStatus.SUCCEEDED
                : ToolInvocationStatus.FAILED);
        invocation.setResult(result.content());
        invocation.setErrorCode(result.errorCode());
        invocation.setErrorMessage(result.errorMessage());
        invocation.setCompletedAt(clock.instant());
        invocationMapper.updateById(invocation);
    }

    private ToolInvocation find(ToolExecutionContext context) {
        return invocationMapper.selectOne(
                Wrappers.<ToolInvocation>lambdaQuery()
                        .eq(ToolInvocation::getAgentRunId, context.agentRunId())
                        .eq(ToolInvocation::getToolCallId, context.toolCallId()));
    }

    private JsonNode parseArguments(String rawArguments) {
        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(rawArguments);
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    AgentErrorCode.TOOL_ARGUMENTS_INVALID, "工具参数必须是合法 JSON");
        }
        if (arguments == null || !arguments.isObject()) {
            throw new BusinessException(
                    AgentErrorCode.TOOL_ARGUMENTS_INVALID, "工具参数必须是 JSON 对象");
        }
        return arguments;
    }

    private void requireContext(ToolExecutionContext context) {
        if (context == null
                || context.userId() == null
                || context.agentRunId() == null
                || context.toolCallId() == null
                || context.toolCallId().isBlank()) {
            throw new BusinessException(
                    AgentErrorCode.TOOL_ARGUMENTS_INVALID, "工具执行上下文不完整");
        }
    }
}
