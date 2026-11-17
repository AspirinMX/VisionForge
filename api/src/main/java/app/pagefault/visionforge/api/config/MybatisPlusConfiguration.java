package app.pagefault.visionforge.api.config;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

import app.pagefault.visionforge.api.persistence.UuidTypeHandler;

@Configuration(proxyBeanMethods = false)
@MapperScan(
        basePackages = {"app.pagefault.visionforge.identity", "app.pagefault.visionforge.api.audit"},
        annotationClass = Mapper.class)
public class MybatisPlusConfiguration {

    private static final long MAX_PAGE_SIZE = 200L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    @Bean
    public ConfigurationCustomizer uuidTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(UUID.class, new UuidTypeHandler());
    }
}
