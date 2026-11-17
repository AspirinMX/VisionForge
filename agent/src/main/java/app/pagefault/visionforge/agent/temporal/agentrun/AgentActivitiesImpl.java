package app.pagefault.visionforge.agent.temporal.agentrun;

import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.model.ModelProviderSettings;
import app.pagefault.visionforge.agent.provider.ChatCompletionRequest;
import app.pagefault.visionforge.agent.provider.ChatCompletionResult;
import app.pagefault.visionforge.agent.provider.ChatModelClient;
import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.runtime.ModelInvocationStatus;
import app.pagefault.visionforge.common.error.BusinessException;

import io.temporal.failure.ApplicationFailure;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AgentActivitiesImpl implements AgentActivities {

    private final AgentRunService agentRunService;
    private final ModelDefinitionService modelDefinitionService;
    private final ChatModelClient chatModelClient;

    @Override
    public AgentPrompt loadPrompt(UUID runId) {
        try {
            return agentRunService.loadPrompt(runId);
        } catch (BusinessException exception) {
            agentRunService.fail(runId, exception.getMessage());
            throw ApplicationFailure.newNonRetryableFailure(
                    exception.getMessage(), exception.getErrorCode().code());
        }
    }

    @Override
    public ModelReply invokeModel(UUID runId, AgentPrompt prompt) {
        ModelDefinition model = modelDefinitionService.get(prompt.modelDefinitionId());
        try {
            ModelProviderSettings settings = modelDefinitionService.settings(model);
            ChatCompletionRequest request = new ChatCompletionRequest(
                    settings.baseUrl(),
                    settings.apiKey(),
                    settings.modelId(),
                    settings.reasoningEffort(),
                    settings.maxOutputTokens(),
                    prompt.turns());
            ChatCompletionResult result = chatModelClient.complete(request);
            agentRunService.recordInvocation(
                    runId, model.getId(), ModelInvocationStatus.SUCCEEDED, result, null);
            return new ModelReply(
                    result.content(),
                    result.inputTokens(),
                    result.outputTokens(),
                    result.finishReason(),
                    result.latencyMs());
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? AgentErrorCode.MODEL_PROVIDER_ERROR.message()
                    : exception.getMessage();
            agentRunService.recordInvocation(
                    runId, model.getId(), ModelInvocationStatus.FAILED, null, message);
            agentRunService.fail(runId, message);
            throw ApplicationFailure.newNonRetryableFailure(
                    message, AgentErrorCode.MODEL_PROVIDER_ERROR.code());
        }
    }

    @Override
    public void completeRun(UUID runId, ModelReply reply) {
        agentRunService.complete(runId, reply);
    }

    @Override
    public void failRun(UUID runId, String errorMessage) {
        agentRunService.fail(runId, errorMessage);
    }
}
