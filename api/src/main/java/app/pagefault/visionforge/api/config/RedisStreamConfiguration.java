package app.pagefault.visionforge.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class RedisStreamConfiguration {

    @Bean(destroyMethod = "destroy")
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            ThreadPoolTaskExecutor redisSubscriptionExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Preserve event order: model deltas must reach the SSE response in the order emitted.
        container.setTaskExecutor(new SyncTaskExecutor());
        container.setSubscriptionExecutor(redisSubscriptionExecutor);
        return container;
    }

    @Bean
    public ThreadPoolTaskExecutor redisSubscriptionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("redis-subscription-");
        executor.initialize();
        return executor;
    }
}
