package app.pagefault.visionforge.worker.temporal;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.pagefault.visionforge.agent.temporal.agentrun.AgentActivitiesImpl;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentRunWorkflowImpl;
import app.pagefault.visionforge.agent.temporal.composition.CompositionActivitiesImpl;
import app.pagefault.visionforge.agent.temporal.composition.VideoCompositionWorkflowImpl;
import app.pagefault.visionforge.agent.temporal.generation.MediaGenerationActivitiesImpl;
import app.pagefault.visionforge.agent.temporal.generation.MediaGenerationWorkflowImpl;
import app.pagefault.visionforge.agent.temporal.selfcheck.SelfCheckActivitiesImpl;
import app.pagefault.visionforge.agent.temporal.selfcheck.SelfCheckWorkflowImpl;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.common.id.UuidV7Generator;
import app.pagefault.visionforge.worker.config.WorkerProperties;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
public class TemporalWorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerConfiguration.class);

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public IdGenerator idGenerator(Clock clock) {
        return new UuidV7Generator(clock);
    }

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

    /**
     * The factory lifecycle is managed by {@link TemporalWorkerLifecycle} so shutdown can honor the
     * configured grace period before forcing termination.
     */
    @Bean(destroyMethod = "")
    public WorkerFactory workerFactory(
            WorkflowClient workflowClient,
            AgentActivitiesImpl agentActivities,
            MediaGenerationActivitiesImpl mediaGenerationActivities,
            CompositionActivitiesImpl compositionActivities,
            WorkerProperties properties,
            @Value("${visionforge.temporal.task-queue}") String taskQueue) {
        WorkerFactoryOptions factoryOptions = WorkerFactoryOptions.newBuilder()
                .setWorkflowCacheSize(properties.workflowCacheSize())
                .setMaxWorkflowThreadCount(properties.maxWorkflowThreadCount())
                .build();
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient, factoryOptions);

        WorkerOptions workerOptions = WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(
                        properties.maxConcurrentActivityExecutionSize())
                .setMaxConcurrentWorkflowTaskExecutionSize(
                        properties.maxConcurrentWorkflowTaskExecutionSize())
                .setMaxConcurrentLocalActivityExecutionSize(
                        properties.maxConcurrentLocalActivityExecutionSize())
                .setMaxWorkerActivitiesPerSecond(properties.maxWorkerActivitiesPerSecond())
                .setMaxTaskQueueActivitiesPerSecond(properties.maxTaskQueueActivitiesPerSecond())
                .setMaxConcurrentWorkflowTaskPollers(properties.maxConcurrentWorkflowTaskPollers())
                .setMaxConcurrentActivityTaskPollers(properties.maxConcurrentActivityTaskPollers())
                .build();

        Worker worker = factory.newWorker(taskQueue, workerOptions);
        worker.registerWorkflowImplementationTypes(
                SelfCheckWorkflowImpl.class,
                AgentRunWorkflowImpl.class,
                MediaGenerationWorkflowImpl.class,
                VideoCompositionWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new SelfCheckActivitiesImpl(),
                agentActivities,
                mediaGenerationActivities,
                compositionActivities);
        factory.start();
        log.info(
                "Temporal worker started on task queue '{}' (activities={}, workflowTasks={}, "
                        + "workflowCache={}, maxWorkflowThreads={})",
                taskQueue,
                properties.maxConcurrentActivityExecutionSize(),
                properties.maxConcurrentWorkflowTaskExecutionSize(),
                properties.workflowCacheSize(),
                properties.maxWorkflowThreadCount());
        return factory;
    }
}
