package app.pagefault.visionforge.asset.config;

import java.time.Clock;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;

import app.pagefault.visionforge.asset.library.ConversationFileMapper;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationFileVersionMapper;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.media.AssetMapper;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.asset.persistence.AssetUuidTypeHandler;
import app.pagefault.visionforge.asset.storage.ObjectStorage;
import app.pagefault.visionforge.asset.storage.StorageProperties;
import app.pagefault.visionforge.asset.upload.UploadSessionMapper;
import app.pagefault.visionforge.common.id.IdGenerator;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "app.pagefault.visionforge.asset", annotationClass = Mapper.class)
@EnableConfigurationProperties(StorageProperties.class)
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

    @Bean
    public AssetService assetService(
            AssetMapper assetMapper,
            UploadSessionMapper uploadSessionMapper,
            ConversationReferenceChecker conversationReferenceChecker,
            ObjectProvider<ObjectStorage> storageProvider,
            StorageProperties storageProperties,
            IdGenerator idGenerator,
            Clock clock) {
        return new AssetService(
                assetMapper,
                uploadSessionMapper,
                conversationReferenceChecker,
                storageProvider,
                storageProperties,
                idGenerator,
                clock);
    }
}
