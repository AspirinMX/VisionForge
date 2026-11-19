package app.pagefault.visionforge.common.config;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers PostgreSQL-backed runtime configuration with per-instance version refresh.
 */
@Configuration(proxyBeanMethods = false)
public class DynamicConfigConfiguration {

    @Bean
    public ConfigStore configStore(DataSource dataSource) {
        return new JdbcConfigStore(new JdbcTemplate(dataSource));
    }

    @Bean(initMethod = "init", destroyMethod = "close")
    public DynamicRuntimeConfig dynamicRuntimeConfig(
            ConfigStore configStore,
            @Value("${visionforge.config.refresh-interval-ms:5000}") long refreshIntervalMs,
            ObjectProvider<Clock> clockProvider) {
        return new DynamicRuntimeConfig(
                configStore,
                refreshIntervalMs,
                clockProvider.getIfAvailable(Clock::systemUTC));
    }
}
