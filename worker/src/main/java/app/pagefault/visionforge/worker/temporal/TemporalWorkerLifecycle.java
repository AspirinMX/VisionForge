package app.pagefault.visionforge.worker.temporal;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.worker.config.WorkerProperties;

import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PreDestroy;

@Component
public class TemporalWorkerLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerLifecycle.class);

    private final WorkerFactory workerFactory;
    private final WorkerProperties properties;

    public TemporalWorkerLifecycle(WorkerFactory workerFactory, WorkerProperties properties) {
        this.workerFactory = workerFactory;
        this.properties = properties;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Temporal worker");
        workerFactory.shutdown();
        workerFactory.awaitTermination(
                properties.gracefulShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!workerFactory.isTerminated()) {
            log.warn("Temporal worker did not stop gracefully; forcing shutdown");
            workerFactory.shutdownNow();
        }
    }
}
