package app.pagefault.visionforge.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import app.pagefault.visionforge.common.config.DynamicConfigConfiguration;

@EnableScheduling
@Import(DynamicConfigConfiguration.class)
@SpringBootApplication(scanBasePackages = {
        "app.pagefault.visionforge.worker",
        "app.pagefault.visionforge.agent",
        "app.pagefault.visionforge.asset",
        "app.pagefault.visionforge.billing"
})
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
