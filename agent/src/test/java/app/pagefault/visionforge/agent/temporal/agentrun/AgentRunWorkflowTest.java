package app.pagefault.visionforge.agent.temporal.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.agent.provider.ChatTurn;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

class AgentRunWorkflowTest {

    private static final String TASK_QUEUE = "agent-run-test";

    @Test
    void completesRunThroughActivities() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        try {
            CapturingActivities activities = new CapturingActivities();
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(AgentRunWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();

            AgentRunWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    AgentRunWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
            UUID runId = UUID.randomUUID();

            workflow.run(runId);

            assertThat(activities.prompt.runId()).isEqualTo(runId);
            assertThat(activities.completed).isTrue();
            assertThat(activities.failed).isFalse();
        } finally {
            environment.close();
        }
    }

    @Test
    void marksRunFailedWhenModelActivityFails() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        try {
            CapturingActivities activities = new CapturingActivities();
            activities.failModel = true;
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(AgentRunWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();

            WorkflowClient client = environment.getWorkflowClient();
            AgentRunWorkflow workflow = client.newWorkflowStub(
                    AgentRunWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

            assertThatThrownBy(() -> workflow.run(UUID.randomUUID()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(activities.failed).isTrue();
        } finally {
            environment.close();
        }
    }

    static class CapturingActivities implements AgentActivities {

        private AgentPrompt prompt;
        private boolean completed;
        private boolean failed;
        private boolean failModel;

        @Override
        public AgentPrompt loadPrompt(UUID runId) {
            prompt = new AgentPrompt(
                    runId,
                    UUID.randomUUID(),
                    1_000,
                    List.of(new ChatTurn("system", "system"), new ChatTurn("user", "hello")));
            return prompt;
        }

        @Override
        public ModelReply invokeModel(UUID runId, AgentPrompt prompt) {
            if (failModel) {
                throw ApplicationFailure.newNonRetryableFailure("boom", "MODEL_FAILED");
            }
            return new ModelReply("reply", 10, 5, "stop", 20);
        }

        @Override
        public void completeRun(UUID runId, ModelReply reply) {
            completed = true;
        }

        @Override
        public void failRun(UUID runId, String errorMessage) {
            failed = true;
        }
    }
}
