package app.pagefault.visionforge.worker.temporal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.pagefault.visionforge.agent.temporal.selfcheck.SelfCheckActivitiesImpl;
import app.pagefault.visionforge.agent.temporal.selfcheck.SelfCheckWorkflowImpl;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

@Configuration(proxyBeanMethods = false)
public class TemporalWorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(
            @Value("${visionforge.temporal.target}") String target) {
        log.info("Connecting to Temporal at {}", target);
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(target)
                        .build());
    }

    @Bean
    public WorkflowClient workflowClient(
            WorkflowServiceStubs workflowServiceStubs,
            @Value("${visionforge.temporal.namespace}") String namespace) {
        return WorkflowClient.newInstance(
                workflowServiceStubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(namespace)
                        .build());
    }

    @Bean(destroyMethod = "shutdown")
    public WorkerFactory workerFactory(
            WorkflowClient workflowClient,
            @Value("${visionforge.temporal.task-queue}") String taskQueue) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(taskQueue);
        worker.registerWorkflowImplementationTypes(SelfCheckWorkflowImpl.class);
        worker.registerActivitiesImplementations(new SelfCheckActivitiesImpl());
        factory.start();
        log.info("Temporal worker started on task queue '{}'", taskQueue);
        return factory;
    }
}
