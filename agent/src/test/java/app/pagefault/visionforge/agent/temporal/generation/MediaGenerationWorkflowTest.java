package app.pagefault.visionforge.agent.temporal.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

class MediaGenerationWorkflowTest {

    private static final String TASK_QUEUE = "media-generation-test";

    @Test
    void settlesAfterInlineCompletion() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        try {
            CapturingActivities activities = new CapturingActivities();
            startWorker(environment, activities);

            workflow(environment).run(UUID.randomUUID());

            assertThat(activities.submitted).isEqualTo(1);
            assertThat(activities.polled).isEqualTo(0);
            assertThat(activities.settled).isEqualTo(1);
            assertThat(activities.failed).isFalse();
        } finally {
            environment.close();
        }
    }

    @Test
    void pollsUntilRemoteTaskCompletes() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        try {
            CapturingActivities activities = new CapturingActivities();
            activities.pendingPolls = 2;
            startWorker(environment, activities);

            workflow(environment).run(UUID.randomUUID());

            assertThat(activities.submitted).isEqualTo(1);
            assertThat(activities.polled).isEqualTo(2);
            assertThat(activities.settled).isEqualTo(1);
        } finally {
            environment.close();
        }
    }

    @Test
    void recordsFailureWhenSubmitFails() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        try {
            CapturingActivities activities = new CapturingActivities();
            activities.failSubmit = true;
            startWorker(environment, activities);

            assertThatThrownBy(() -> workflow(environment).run(UUID.randomUUID()))
                    .isInstanceOf(RuntimeException.class);
            assertThat(activities.failed).isTrue();
            assertThat(activities.settled).isEqualTo(0);
        } finally {
            environment.close();
        }
    }

    private MediaGenerationWorkflow workflow(TestWorkflowEnvironment environment) {
        return environment.getWorkflowClient().newWorkflowStub(
                MediaGenerationWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
    }

    private void startWorker(
            TestWorkflowEnvironment environment, CapturingActivities activities) {
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(MediaGenerationWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        environment.start();
    }

    static class CapturingActivities implements MediaGenerationActivities {

        private final List<UUID> submits = new ArrayList<>();
        private int submitted;
        private int polled;
        private int settled;
        private boolean failed;
        private int pendingPolls;
        private boolean failSubmit;

        @Override
        public GenerationSubmitResult submit(UUID taskId) {
            submitted++;
            submits.add(taskId);
            if (failSubmit) {
                throw ApplicationFailure.newNonRetryableFailure("boom", "SUBMIT_FAILED");
            }
            return new GenerationSubmitResult(pendingPolls > 0, false);
        }

        @Override
        public GenerationSubmitResult poll(UUID taskId) {
            polled++;
            return new GenerationSubmitResult(polled < pendingPolls, false);
        }

        @Override
        public void settle(UUID taskId) {
            settled++;
        }

        @Override
        public void fail(UUID taskId, String errorMessage) {
            failed = true;
        }
    }
}
