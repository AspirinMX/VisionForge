package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.model.ModelProviderSettings;
import app.pagefault.visionforge.agent.provider.ModelRequest;
import app.pagefault.visionforge.agent.provider.ModelResponse;
import app.pagefault.visionforge.agent.provider.ModelToolCall;
import app.pagefault.visionforge.agent.provider.ModelToolSpec;
import app.pagefault.visionforge.agent.provider.ResponsesModelClient;
import app.pagefault.visionforge.agent.runtime.AgentModelInput;
import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.runtime.AgentRunTurn;
import app.pagefault.visionforge.agent.runtime.ModelInvocationStatus;
import app.pagefault.visionforge.agent.stream.AgentRunStreamPublisher;
import app.pagefault.visionforge.agent.tool.AgentToolExecutor;
import app.pagefault.visionforge.agent.tool.ToolDefinition;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.tool.ToolRegistry;
import app.pagefault.visionforge.common.error.BusinessException;

import io.temporal.failure.ApplicationFailure;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AgentActivitiesImpl implements AgentActivities {

    private final AgentRunService agentRunService;
    private final ModelDefinitionService modelDefinitionService;
    private final ResponsesModelClient responsesModelClient;
    private final ToolRegistry toolRegistry;
    private final AgentToolExecutor toolExecutor;
    private final AgentRunStreamPublisher streamPublisher;

    @Override
    public AgentPrompt loadPrompt(UUID runId) {
        try {
            return agentRunService.loadPrompt(runId);
        } catch (BusinessException exception) {
            failRunAndNotify(runId, exception);
            throw ApplicationFailure.newNonRetryableFailure(
                    exception.getMessage(), exception.getErrorCode().code());
        }
    }

    @Override
    public ModelTurn invokeModel(UUID runId, int turnNo) {
        AgentRunTurn existing = agentRunService.findTurn(runId, turnNo);
        if (existing != null) {
            return replay(existing);
        }

        ModelDefinition model = null;
        try {
            AgentRun run = agentRunService.get(runId);
            model = modelDefinitionService.get(run.getModelDefinitionId());
            ModelProviderSettings settings = modelDefinitionService.settings(model);
            AgentModelInput input = agentRunService.buildInput(runId);

            ModelResponse response = responsesModelClient.stream(
                    new ModelRequest(
                            settings.baseUrl(),
                            settings.apiKey(),
                            settings.modelId(),
                            settings.reasoningEffort(),
                            settings.maxOutputTokens(),
                            input.instructions(),
                            input.input(),
                            toolSpecs()),
                    delta -> streamPublisher.publishDelta(runId, delta));

            agentRunService.recordTurn(runId, turnNo, response);
            agentRunService.recordInvocation(
                    runId, model.getId(), ModelInvocationStatus.SUCCEEDED, response, null);
            return new ModelTurn(
                    turnNo,
                    response.toolCalls().stream().map(ModelToolCall::callId).toList(),
                    response.responseId());
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? AgentErrorCode.MODEL_PROVIDER_ERROR.message()
                    : exception.getMessage();
            recordFailure(runId, model, message);
            throw ApplicationFailure.newNonRetryableFailure(
                    message, AgentErrorCode.MODEL_PROVIDER_ERROR.code());
        }
    }

    @Override
    public void executeTool(UUID runId, String toolCallId) {
        ModelToolCall call = agentRunService.findToolCall(runId, toolCallId);
        if (call == null) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "工具调用记录不存在", AgentErrorCode.TOOL_NOT_FOUND.code());
        }
        ToolExecutionContext context = agentRunService.toolContext(runId, toolCallId);
        toolExecutor.execute(context, call.name(), call.arguments());
    }

    @Override
    public void completeRun(UUID runId, int turnNo) {
        UUID assistantMessageId = agentRunService.complete(runId, turnNo);
        streamPublisher.publishDone(runId, assistantMessageId);
    }

    @Override
    public void failRun(UUID runId, String errorMessage) {
        if (agentRunService.fail(runId, errorMessage)) {
            streamPublisher.publishError(runId, errorMessage);
        }
    }

    private ModelTurn replay(AgentRunTurn turn) {
        List<ModelToolCall> calls = agentRunService.toolCalls(
                turn.getAgentRunId(), turn.getTurnNo());
        return new ModelTurn(
                turn.getTurnNo(),
                calls.stream().map(ModelToolCall::callId).toList(),
                turn.getResponseId());
    }

    private List<ModelToolSpec> toolSpecs() {
        return toolRegistry.definitions().stream()
                .map(this::toSpec)
                .toList();
    }

    private ModelToolSpec toSpec(ToolDefinition definition) {
        return new ModelToolSpec(
                definition.name(), definition.description(), definition.inputSchema());
    }

    private void recordFailure(UUID runId, ModelDefinition model, String message) {
        try {
            if (model != null) {
                agentRunService.recordInvocation(
                        runId, model.getId(), ModelInvocationStatus.FAILED, null, message);
            }
            if (agentRunService.fail(runId, message)) {
                streamPublisher.publishError(runId, message);
            }
        } catch (RuntimeException bookkeepingFailure) {
            // The original model failure is what the workflow and the user must see.
        }
    }

    private void failRunAndNotify(UUID runId, BusinessException exception) {
        try {
            if (agentRunService.fail(runId, exception.getMessage())) {
                streamPublisher.publishError(runId, exception.getMessage());
            }
        } catch (RuntimeException ignored) {
            // The run itself may be missing or already terminal.
        }
    }
}
