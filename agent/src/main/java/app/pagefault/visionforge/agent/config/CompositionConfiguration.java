package app.pagefault.visionforge.agent.config;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.pagefault.visionforge.agent.composition.CompositionInputMapper;
import app.pagefault.visionforge.agent.composition.CompositionProperties;
import app.pagefault.visionforge.agent.composition.CompositionTaskMapper;
import app.pagefault.visionforge.agent.composition.CompositionTaskService;
import app.pagefault.visionforge.agent.composition.FfmpegCommandBuilder;
import app.pagefault.visionforge.agent.composition.FfmpegRunner;
import app.pagefault.visionforge.agent.composition.ProcessBuilderFfmpegRunner;
import app.pagefault.visionforge.agent.generation.GenerationTaskMapper;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequestMapper;
import app.pagefault.visionforge.agent.storyboard.StoryboardService;
import app.pagefault.visionforge.agent.temporal.composition.CompositionActivitiesImpl;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.asset.storage.ObjectStorage;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CompositionProperties.class)
public class CompositionConfiguration {

    @Bean
    public FfmpegCommandBuilder ffmpegCommandBuilder() {
        return new FfmpegCommandBuilder();
    }

    @Bean
    public FfmpegRunner ffmpegRunner() {
        return new ProcessBuilderFfmpegRunner();
    }

    @Bean
    public CompositionTaskService compositionTaskService(
            CompositionTaskMapper compositionTaskMapper,
            CompositionInputMapper compositionInputMapper,
            WorkflowStartRequestMapper workflowStartRequestMapper,
            ConversationReferenceChecker conversationReferenceChecker,
            StoryboardService storyboardService,
            GenerationTaskMapper generationTaskMapper,
            AssetService assetService,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            Clock clock) {
        return new CompositionTaskService(
                compositionTaskMapper,
                compositionInputMapper,
                workflowStartRequestMapper,
                conversationReferenceChecker,
                storyboardService,
                generationTaskMapper,
                assetService,
                objectMapper,
                idGenerator,
                clock);
    }

    @Bean
    public CompositionActivitiesImpl compositionActivities(
            CompositionTaskService compositionTaskService,
            AssetService assetService,
            ConversationFileService conversationFileService,
            ObjectProvider<ObjectStorage> storageProvider,
            FfmpegCommandBuilder ffmpegCommandBuilder,
            FfmpegRunner ffmpegRunner,
            CompositionProperties compositionProperties,
            ObjectMapper objectMapper) {
        return new CompositionActivitiesImpl(
                compositionTaskService,
                assetService,
                conversationFileService,
                storageProvider,
                ffmpegCommandBuilder,
                ffmpegRunner,
                compositionProperties,
                objectMapper);
    }
}
