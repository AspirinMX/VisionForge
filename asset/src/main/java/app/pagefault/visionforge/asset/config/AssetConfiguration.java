package app.pagefault.visionforge.asset.config;

import java.time.Clock;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;

import app.pagefault.visionforge.asset.library.ConversationFileMapper;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationFileVersionMapper;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.persistence.AssetUuidTypeHandler;
import app.pagefault.visionforge.common.id.IdGenerator;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "app.pagefault.visionforge.asset", annotationClass = Mapper.class)
public class AssetConfiguration {

    @Bean
    public ConfigurationCustomizer assetUuidTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(UUID.class, new AssetUuidTypeHandler());
    }

    @Bean
    public ConversationFileService conversationFileService(
            ConversationFileMapper conversationFileMapper,
            ConversationFileVersionMapper conversationFileVersionMapper,
            ConversationReferenceChecker conversationReferenceChecker,
            IdGenerator idGenerator,
            Clock clock) {
        return new ConversationFileService(
                conversationFileMapper,
                conversationFileVersionMapper,
                conversationReferenceChecker,
                idGenerator,
                clock);
    }
}
