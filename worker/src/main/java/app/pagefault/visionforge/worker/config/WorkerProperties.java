package app.pagefault.visionforge.worker.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "visionforge.worker")
public record WorkerProperties(
        int maxConcurrentActivityExecutionSize,
        int maxConcurrentWorkflowTaskExecutionSize,
        int maxConcurrentLocalActivityExecutionSize,
        double maxWorkerActivitiesPerSecond,
        double maxTaskQueueActivitiesPerSecond,
        int maxConcurrentWorkflowTaskPollers,
        int maxConcurrentActivityTaskPollers,
        int workflowCacheSize,
        int maxWorkflowThreadCount,
        Duration gracefulShutdownTimeout,
        StartCompensation startCompensation) {

    public record StartCompensation(
            boolean enabled,
            long intervalMs,
            long initialDelayMs,
            int batchSize,
            int maxAttempts) {
    }
}
