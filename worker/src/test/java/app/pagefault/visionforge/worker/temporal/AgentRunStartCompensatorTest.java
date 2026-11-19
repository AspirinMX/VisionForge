package app.pagefault.visionforge.worker.temporal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.agent.composition.CompositionTaskService;
import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequest;
import app.pagefault.visionforge.agent.runtime.WorkflowStartType;
import app.pagefault.visionforge.common.alert.AlertEvent;
import app.pagefault.visionforge.common.alert.AlertPublisher;
import app.pagefault.visionforge.worker.config.WorkerProperties;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunStartCompensatorTest {

    @SuppressWarnings("unchecked")
    @Test
    void emitsAlertWhenWorkflowStartCompensationAttemptsExhausted() {
        WorkflowClient workflowClient = mock(WorkflowClient.class);
        when(workflowClient.newWorkflowStub(any(Class.class), any(WorkflowOptions.class)))
                .thenThrow(new IllegalStateException("temporal unavailable"));

        AgentRunService agentRunService = mock(AgentRunService.class);
        GenerationTaskService generationTaskService = mock(GenerationTaskService.class);
        CompositionTaskService compositionTaskService = mock(CompositionTaskService.class);
        AlertPublisher alertPublisher = mock(AlertPublisher.class);

        WorkerProperties props = new WorkerProperties(
                10, 10, 10, 0, 0, 2, 2, 100, 100,
                Duration.ofSeconds(10),
                new WorkerProperties.StartCompensation(true, 5000, 5000, 10, 3));

        WorkflowStartRequest retryableReq = new WorkflowStartRequest();
        retryableReq.setId(UUID.randomUUID());
        retryableReq.setAgentRunId(UUID.randomUUID());
        retryableReq.setBusinessId(retryableReq.getAgentRunId());
        retryableReq.setWorkflowType(WorkflowStartType.AGENT_RUN);
        retryableReq.setWorkflowId("agent-run-retryable");
        retryableReq.setAttempts(0);

        WorkflowStartRequest exhaustedReq = new WorkflowStartRequest();
        exhaustedReq.setId(UUID.randomUUID());
        exhaustedReq.setAgentRunId(UUID.randomUUID());
        exhaustedReq.setBusinessId(exhaustedReq.getAgentRunId());
        exhaustedReq.setWorkflowType(WorkflowStartType.AGENT_RUN);
        exhaustedReq.setWorkflowId("agent-run-exhausted");
        exhaustedReq.setAttempts(2);

        when(agentRunService.pendingStartRequests(10)).thenReturn(List.of(retryableReq, exhaustedReq));
        when(generationTaskService.pendingStartRequests(10)).thenReturn(List.of());
        when(compositionTaskService.pendingStartRequests(10)).thenReturn(List.of());
        doAnswer(inv -> {
            WorkflowStartRequest req = inv.getArgument(0);
            req.setAttempts(req.getAttempts() + 1);
            return null;
        }).when(agentRunService).recordWorkflowStartFailure(any(), any(), eq(3));

        AgentRunStartCompensator compensator = new AgentRunStartCompensator(
                workflowClient,
                agentRunService,
                generationTaskService,
                compositionTaskService,
                props,
                "vision-forge",
                alertPublisher);

        compensator.compensate();

        verify(alertPublisher).publish(any(AlertEvent.class));
        assertThat(exhaustedReq.getAttempts()).isEqualTo(3);
        verify(alertPublisher, never()).publish(null);
    }
}
