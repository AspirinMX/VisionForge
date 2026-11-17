package app.pagefault.visionforge.api.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.common.id.UuidV7Generator;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public IdGenerator idGenerator(Clock clock) {
        return new UuidV7Generator(clock);
    }
}
