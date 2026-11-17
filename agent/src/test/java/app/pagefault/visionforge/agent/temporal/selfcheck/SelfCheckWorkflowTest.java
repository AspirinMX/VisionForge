package app.pagefault.visionforge.agent.temporal.selfcheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

class SelfCheckWorkflowTest {

    private static final String TASK_QUEUE = "self-check";

    @Test
    void executesWorkflowAndActivity() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        try {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(SelfCheckWorkflowImpl.class);
            worker.registerActivitiesImplementations(new SelfCheckActivitiesImpl());
            environment.start();

            WorkflowClient client = environment.getWorkflowClient();
            SelfCheckWorkflow workflow = client.newWorkflowStub(
                    SelfCheckWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

            assertThat(workflow.run("forge")).isEqualTo("pong:forge");
        } finally {
            environment.close();
        }
    }
}
