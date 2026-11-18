package app.pagefault.visionforge.agent.config;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;

import app.pagefault.visionforge.agent.conversation.AgentConversationReferenceChecker;
import app.pagefault.visionforge.agent.conversation.ConversationMapper;
import app.pagefault.visionforge.agent.conversation.ConversationService;
import app.pagefault.visionforge.agent.conversation.MessageMapper;
import app.pagefault.visionforge.agent.conversation.MessagePartMapper;
import app.pagefault.visionforge.agent.context.ContextCompactor;
import app.pagefault.visionforge.agent.context.ContextSummaryMapper;
import app.pagefault.visionforge.agent.generation.GenerationAttemptMapper;
import app.pagefault.visionforge.agent.generation.GenerationInputMapper;
import app.pagefault.visionforge.agent.generation.GenerationOutputMapper;
import app.pagefault.visionforge.agent.generation.GenerationTaskMapper;
import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.agent.provider.media.ArkClientFactory;
import app.pagefault.visionforge.agent.provider.media.ArkImagesProvider;
import app.pagefault.visionforge.agent.provider.media.ArkVideoProvider;
import app.pagefault.visionforge.agent.provider.media.DashScopeImageMessagesProvider;
import app.pagefault.visionforge.agent.provider.media.DashScopeT2iProvider;
import app.pagefault.visionforge.agent.provider.media.DashScopeVideoProvider;
import app.pagefault.visionforge.agent.provider.media.HttpMediaDownloader;
import app.pagefault.visionforge.agent.provider.media.MediaProviderRegistry;
import app.pagefault.visionforge.agent.provider.media.OpenAiImagesProvider;
import app.pagefault.visionforge.agent.storyboard.StoryboardService;
import app.pagefault.visionforge.agent.storyboard.StoryboardValidator;
import app.pagefault.visionforge.agent.temporal.generation.MediaGenerationActivitiesImpl;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.billing.credit.CreditService;
import app.pagefault.visionforge.agent.model.ApiKeyCipher;
import app.pagefault.visionforge.agent.model.ModelDefinitionMapper;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.persistence.AgentUuidTypeHandler;
import app.pagefault.visionforge.agent.provider.OpenAiResponsesModelClient;
import app.pagefault.visionforge.agent.provider.ResponsesModelClient;
import app.pagefault.visionforge.agent.runtime.AgentRunMapper;
import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.runtime.AgentRunTurnMapper;
import app.pagefault.visionforge.agent.runtime.ModelInvocationMapper;
import app.pagefault.visionforge.agent.runtime.ModelInvocationRecorder;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequestMapper;
import app.pagefault.visionforge.agent.stream.AgentRunStreamPublisher;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentActivitiesImpl;
import app.pagefault.visionforge.agent.tool.AgentToolExecutor;
import app.pagefault.visionforge.agent.tool.ToolInvocationMapper;
import app.pagefault.visionforge.agent.tool.ToolRegistry;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "app.pagefault.visionforge.agent", annotationClass = Mapper.class)
public class AgentConfiguration {

    @Bean
    public ConfigurationCustomizer agentUuidTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(UUID.class, new AgentUuidTypeHandler());
    }

    @Bean
    public ApiKeyCipher apiKeyCipher(
            @Value("${visionforge.model.config-secret:}") String configSecret) {
        return new ApiKeyCipher(configSecret);
    }

    @Bean
    public ResponsesModelClient responsesModelClient() {
        return new OpenAiResponsesModelClient();
    }

    @Bean
    public AgentRunStreamPublisher agentRunStreamPublisher(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        return new AgentRunStreamPublisher(redisTemplate, objectMapper);
    }

    @Bean
    public ModelDefinitionService modelDefinitionService(
            ModelDefinitionMapper modelDefinitionMapper,
            ApiKeyCipher apiKeyCipher,
            IdGenerator idGenerator,
            Clock clock) {
        return new ModelDefinitionService(
                modelDefinitionMapper, apiKeyCipher, idGenerator, clock);
    }

    @Bean
    public ConversationReferenceChecker conversationReferenceChecker(
            ConversationMapper conversationMapper) {
        return new AgentConversationReferenceChecker(conversationMapper);
    }

    @Bean
    public ConversationService conversationService(
            ConversationMapper conversationMapper,
            MessageMapper messageMapper,
            MessagePartMapper messagePartMapper,
            AgentRunMapper agentRunMapper,
            WorkflowStartRequestMapper workflowStartRequestMapper,
            ModelDefinitionService modelDefinitionService,
            AssetService assetService,
            IdGenerator idGenerator,
            Clock clock) {
        return new ConversationService(
                conversationMapper,
                messageMapper,
                messagePartMapper,
                agentRunMapper,
                workflowStartRequestMapper,
                modelDefinitionService,
                assetService,
                idGenerator,
                clock);
    }

    @Bean
    public ModelInvocationRecorder modelInvocationRecorder(
            ModelInvocationMapper modelInvocationMapper,
            IdGenerator idGenerator,
            Clock clock) {
        return new ModelInvocationRecorder(modelInvocationMapper, idGenerator, clock);
    }

    @Bean
    public ContextCompactor contextCompactor(
            ContextSummaryMapper contextSummaryMapper,
            ModelDefinitionService modelDefinitionService,
            ResponsesModelClient responsesModelClient,
            ModelInvocationRecorder modelInvocationRecorder,
            IdGenerator idGenerator,
            Clock clock,
            @Value("${visionforge.context.compact-threshold:0.8}") double compactThreshold,
            @Value("${visionforge.context.compact-keep-ratio:0.25}") double compactKeepRatio,
            @Value("${visionforge.context.summary-max-output-tokens:1024}") int summaryMaxOutputTokens) {
        return new ContextCompactor(
                contextSummaryMapper,
                modelDefinitionService,
                responsesModelClient,
                modelInvocationRecorder,
                idGenerator,
                clock,
                compactThreshold,
                compactKeepRatio,
                summaryMaxOutputTokens);
    }

    @Bean
    public AgentRunService agentRunService(
            AgentRunMapper agentRunMapper,
            ModelInvocationRecorder modelInvocationRecorder,
            AgentRunTurnMapper agentRunTurnMapper,
            ToolInvocationMapper toolInvocationMapper,
            WorkflowStartRequestMapper workflowStartRequestMapper,
            ConversationMapper conversationMapper,
            MessageMapper messageMapper,
            MessagePartMapper messagePartMapper,
            AssetService assetService,
            ModelDefinitionService modelDefinitionService,
            ContextCompactor contextCompactor,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            Clock clock) {
        return new AgentRunService(
                agentRunMapper,
                modelInvocationRecorder,
                agentRunTurnMapper,
                toolInvocationMapper,
                workflowStartRequestMapper,
                conversationMapper,
                messageMapper,
                messagePartMapper,
                assetService,
                modelDefinitionService,
                contextCompactor,
                objectMapper,
                idGenerator,
                clock);
    }

    @Bean
    public StoryboardValidator storyboardValidator(ObjectMapper objectMapper) {
        return new StoryboardValidator(objectMapper);
    }

    @Bean
    public StoryboardService storyboardService(
            ConversationFileService conversationFileService,
            StoryboardValidator storyboardValidator,
            ObjectMapper objectMapper) {
        return new StoryboardService(
                conversationFileService, storyboardValidator, objectMapper);
    }

    @Bean
    public MediaProviderRegistry mediaProviderRegistry(ObjectMapper objectMapper) {
        ArkClientFactory arkClients = new ArkClientFactory();
        HttpMediaDownloader imageDownloader = new HttpMediaDownloader(64L * 1024 * 1024);
        HttpMediaDownloader videoDownloader = new HttpMediaDownloader(1024L * 1024 * 1024);
        return new MediaProviderRegistry(List.of(
                new OpenAiImagesProvider(objectMapper),
                new ArkImagesProvider(objectMapper, arkClients),
                new ArkVideoProvider(objectMapper, arkClients),
                new DashScopeImageMessagesProvider(objectMapper, imageDownloader),
                new DashScopeT2iProvider(objectMapper, imageDownloader),
                new DashScopeVideoProvider(objectMapper, videoDownloader)));
    }

    @Bean
    public GenerationTaskService generationTaskService(
            GenerationTaskMapper generationTaskMapper,
            GenerationAttemptMapper generationAttemptMapper,
            GenerationInputMapper generationInputMapper,
            GenerationOutputMapper generationOutputMapper,
            WorkflowStartRequestMapper workflowStartRequestMapper,
            ModelDefinitionService modelDefinitionService,
            ConversationReferenceChecker conversationReferenceChecker,
            AssetService assetService,
            ConversationFileService conversationFileService,
            StoryboardService storyboardService,
            CreditService creditService,
            MediaProviderRegistry mediaProviderRegistry,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            Clock clock) {
        return new GenerationTaskService(
                generationTaskMapper,
                generationAttemptMapper,
                generationInputMapper,
                generationOutputMapper,
                workflowStartRequestMapper,
                modelDefinitionService,
                conversationReferenceChecker,
                assetService,
                conversationFileService,
                storyboardService,
                creditService,
                mediaProviderRegistry,
                objectMapper,
                idGenerator,
                clock);
    }

    @Bean
    public MediaGenerationActivitiesImpl mediaGenerationActivities(
            GenerationTaskService generationTaskService,
            MediaProviderRegistry mediaProviderRegistry) {
        return new MediaGenerationActivitiesImpl(
                generationTaskService, mediaProviderRegistry);
    }

    @Bean
    public AgentActivitiesImpl agentActivities(
            AgentRunService agentRunService,
            ModelDefinitionService modelDefinitionService,
            ResponsesModelClient responsesModelClient,
            ToolRegistry toolRegistry,
            AgentToolExecutor agentToolExecutor,
            GenerationTaskService generationTaskService,
            AgentRunStreamPublisher streamPublisher) {
        return new AgentActivitiesImpl(
                agentRunService,
                modelDefinitionService,
                responsesModelClient,
                toolRegistry,
                agentToolExecutor,
                generationTaskService,
                streamPublisher);
    }
}
