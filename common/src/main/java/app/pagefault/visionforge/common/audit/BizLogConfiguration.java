package app.pagefault.visionforge.common.audit;

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.common.id.UuidV7Generator;

/**
 * Registers the self-built business audit infrastructure.
 *
 * <p>Applications provide {@link OperatorProvider}, {@link TenantProvider},
 * {@link BizLogStore}, {@link IdGenerator} and {@link Clock} beans as needed; sensible
 * defaults are used otherwise. Import this configuration from api/worker.
 */
@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy
public class BizLogConfiguration {

    @Bean
    public BizLogAspect bizLogAspect(
            ObjectProvider<BizLogStore> storeProvider,
            ObjectProvider<OperatorProvider> operatorProvider,
            ObjectProvider<TenantProvider> tenantProvider,
            ObjectProvider<IdGenerator> idGeneratorProvider,
            ObjectProvider<Clock> clockProvider,
            @Qualifier("bizLogExecutor") Executor bizLogExecutor) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        IdGenerator idGenerator = idGeneratorProvider.getIfAvailable(() -> new UuidV7Generator(clock));
        return new BizLogAspect(
                storeProvider.getIfAvailable(LoggingBizLogStore::new),
                operatorProvider.getIfAvailable(BizLogConfiguration::systemOperatorProvider),
                tenantProvider.getIfAvailable(BizLogConfiguration::noTenantProvider),
                idGenerator,
                clock,
                bizLogExecutor);
    }

    @Bean(name = "bizLogExecutor")
    public ThreadPoolTaskExecutor bizLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1024);
        executor.setThreadNamePrefix("biz-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    private static OperatorProvider systemOperatorProvider() {
        return Operator::system;
    }

    private static TenantProvider noTenantProvider() {
        return () -> null;
    }
}
