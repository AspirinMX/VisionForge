package app.pagefault.visionforge.agent.runtime;

import java.time.Clock;
import java.util.UUID;

import app.pagefault.visionforge.agent.provider.ModelResponse;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;

/**
 * Records one upstream model call. Chat turns and compaction calls share the same table so usage
 * and cost stay auditable, but they are distinguished by {@link ModelInvocationPurpose}.
 */
@RequiredArgsConstructor
public class ModelInvocationRecorder {

    private final ModelInvocationMapper invocationMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public void record(
            UUID agentRunId,
            UUID modelDefinitionId,
            ModelInvocationPurpose purpose,
            ModelInvocationStatus status,
            ModelResponse result,
            String errorMessage) {
        ModelInvocation invocation = new ModelInvocation();
        invocation.setId(idGenerator.nextId());
        invocation.setAgentRunId(agentRunId);
        invocation.setModelDefinitionId(modelDefinitionId);
        invocation.setPurpose(purpose);
        invocation.setStatus(status);
        invocation.setInputTokens(result == null ? null : result.inputTokens());
        invocation.setOutputTokens(result == null ? null : result.outputTokens());
        invocation.setLatencyMs(result == null ? null : result.latencyMs());
        invocation.setFinishReason(result == null ? null : result.finishReason());
        invocation.setResponseId(result == null ? null : result.responseId());
        invocation.setErrorMessage(errorMessage);
        invocation.setCreatedAt(clock.instant());
        invocationMapper.insert(invocation);
    }
}
