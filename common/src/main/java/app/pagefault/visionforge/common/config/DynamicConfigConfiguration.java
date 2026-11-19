package app.pagefault.visionforge.common.config;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Nacos-backed runtime configuration with graceful local fallback when Nacos is disabled.
 */
@Configuration(proxyBeanMethods = false)
public class DynamicConfigConfiguration {

    @Bean
    public NacosSettings nacosSettings(
            @Value("${visionforge.nacos.enabled:false}") boolean enabled,
            @Value("${visionforge.nacos.server-addr:127.0.0.1:8848}") String serverAddr,
            @Value("${visionforge.nacos.namespace:public}") String namespace,
            @Value("${visionforge.nacos.group:VISIONFORGE_GROUP}") String group,
            @Value("${visionforge.nacos.username:nacos}") String username,
            @Value("${visionforge.nacos.password:}") String password,
            @Value("${visionforge.nacos.timeout-ms:5000}") long timeoutMs) {
        return new NacosSettings(enabled, serverAddr, namespace, group, username, password, timeoutMs);
    }

    @Bean
    public ConfigCenterGateway configCenterGateway(NacosSettings settings) {
        return settings.enabled()
                ? new NacosConfigGateway(settings)
                : new LocalConfigGateway();
    }

    @Bean(initMethod = "init", destroyMethod = "close")
    public DynamicRuntimeConfig dynamicRuntimeConfig(
            NacosSettings settings,
            ConfigCenterGateway configCenterGateway,
            ObjectProvider<Clock> clockProvider) {
        return new DynamicRuntimeConfig(
                settings, configCenterGateway, clockProvider.getIfAvailable(Clock::systemUTC));
    }
}
