package app.pagefault.visionforge.billing.config;

import java.time.Clock;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;

import app.pagefault.visionforge.billing.credit.CreditAccountMapper;
import app.pagefault.visionforge.billing.credit.CreditLedgerMapper;
import app.pagefault.visionforge.billing.credit.CreditRedemptionCodeMapper;
import app.pagefault.visionforge.billing.credit.CreditRedemptionRecordMapper;
import app.pagefault.visionforge.billing.credit.CreditReservationMapper;
import app.pagefault.visionforge.billing.credit.CreditService;
import app.pagefault.visionforge.billing.credit.RedemptionService;
import app.pagefault.visionforge.billing.persistence.BillingUuidTypeHandler;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;
import app.pagefault.visionforge.common.id.IdGenerator;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "app.pagefault.visionforge.billing", annotationClass = Mapper.class)
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfiguration {

    @Bean
    public ConfigurationCustomizer billingUuidTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(UUID.class, new BillingUuidTypeHandler());
    }

    @Bean
    public CreditService creditService(
            CreditAccountMapper accountMapper,
            CreditReservationMapper reservationMapper,
            CreditLedgerMapper ledgerMapper,
            IdGenerator idGenerator,
            Clock clock,
            BillingProperties properties,
            ObjectProvider<DynamicRuntimeConfig> runtimeConfigProvider) {
        return new CreditService(
                accountMapper,
                reservationMapper,
                ledgerMapper,
                idGenerator,
                clock,
                properties,
                runtimeConfigProvider.getIfAvailable());
    }

    @Bean
    public RedemptionService redemptionService(
            CreditRedemptionCodeMapper codeMapper,
            CreditRedemptionRecordMapper recordMapper,
            CreditService creditService,
            IdGenerator idGenerator,
            Clock clock) {
        return new RedemptionService(codeMapper, recordMapper, creditService, idGenerator, clock);
    }
}
