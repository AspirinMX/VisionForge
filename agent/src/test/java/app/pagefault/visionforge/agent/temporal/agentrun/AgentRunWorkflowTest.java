package app.pagefault.visionforge.agent.temporal.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

class AgentRunWorkflowTest {

    private static final String TASK_QUEUE = "agent-run-test";

    @Test
    void completesRunWhenModelStopsCallingTools() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        try {
            CapturingActivities activities = new CapturingActivities();
            startWorker(environment, activities);

            workflow(environment).run(UUID.randomUUID());

            assertThat(activities.invokedTurns).containsExactly(0);
            assertThat(activities.executedTools).isEmpty();
            assertThat(activities.completedTurn).isEqualTo(0);
            assertThat(activities.failed).isFalse();
        } finally {
            environment.close();
        }
    }

    @Test
    void executesToolsAndCallsModelAgain() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        try {
            CapturingActivities activities = new CapturingActivities();
            activities.toolCallOnFirstTurn = true;
            startWorker(environment, activities);

            workflow(environment).run(UUID.randomUUID());

            assertThat(activities.invokedTurns).containsExactly(0, 1);
            assertThat(activities.executedTools).containsExactly("call_1");
            assertThat(activities.completedTurn).isEqualTo(1);
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
            startWorker(environment, activities);

            assertThatThrownBy(() -> workflow(environment).run(UUID.randomUUID()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(activities.failed).isTrue();
        } finally {
            environment.close();
        }
    }

    private AgentRunWorkflow workflow(TestWorkflowEnvironment environment) {
        return environment.getWorkflowClient().newWorkflowStub(
                AgentRunWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    }

    private void startWorker(
            TestWorkflowEnvironment environment, CapturingActivities activities) {
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(AgentRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        environment.start();
    }

    static class CapturingActivities implements AgentActivities {

        private final List<Integer> invokedTurns = new ArrayList<>();
        private final List<String> executedTools = new ArrayList<>();
        private boolean toolCallOnFirstTurn;
        private boolean completed;
        private int completedTurn = -1;
        private boolean failed;
        private boolean failModel;

        @Override
        public AgentPrompt loadPrompt(UUID runId) {
            return new AgentPrompt(runId, UUID.randomUUID(), 1_000);
        }

        @Override
        public ModelTurn invokeModel(UUID runId, int turnNo) {
            invokedTurns.add(turnNo);
            if (failModel) {
                throw ApplicationFailure.newNonRetryableFailure("boom", "MODEL_FAILED");
            }
            if (toolCallOnFirstTurn && turnNo == 0) {
                return new ModelTurn(0, List.of("call_1"), "resp_0");
            }
            return new ModelTurn(turnNo, List.of(), "resp_" + turnNo);
        }

        @Override
        public void executeTool(UUID runId, String toolCallId) {
            executedTools.add(toolCallId);
        }

        @Override
        public void completeRun(UUID runId, int turnNo) {
            completed = true;
            completedTurn = turnNo;
        }

        @Override
        public void failRun(UUID runId, String errorMessage) {
            failed = true;
        }
    }
}
