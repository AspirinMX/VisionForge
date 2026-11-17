package app.pagefault.visionforge.agent.config;

import java.time.Clock;
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
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
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
            AgentRunMapper agentRunMapper,
            WorkflowStartRequestMapper workflowStartRequestMapper,
            ModelDefinitionService modelDefinitionService,
            IdGenerator idGenerator,
            Clock clock) {
        return new ConversationService(
                conversationMapper,
                messageMapper,
                agentRunMapper,
                workflowStartRequestMapper,
                modelDefinitionService,
                idGenerator,
                clock);
    }

    @Bean
    public AgentRunService agentRunService(
            AgentRunMapper agentRunMapper,
            ModelInvocationMapper modelInvocationMapper,
            AgentRunTurnMapper agentRunTurnMapper,
            ToolInvocationMapper toolInvocationMapper,
            WorkflowStartRequestMapper workflowStartRequestMapper,
            ConversationMapper conversationMapper,
            MessageMapper messageMapper,
            ModelDefinitionService modelDefinitionService,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            Clock clock) {
        return new AgentRunService(
                agentRunMapper,
                modelInvocationMapper,
                agentRunTurnMapper,
                toolInvocationMapper,
                workflowStartRequestMapper,
                conversationMapper,
                messageMapper,
                modelDefinitionService,
                objectMapper,
                idGenerator,
                clock);
    }

    @Bean
    public AgentActivitiesImpl agentActivities(
            AgentRunService agentRunService,
            ModelDefinitionService modelDefinitionService,
            ResponsesModelClient responsesModelClient,
            ToolRegistry toolRegistry,
            AgentToolExecutor agentToolExecutor,
            AgentRunStreamPublisher streamPublisher) {
        return new AgentActivitiesImpl(
                agentRunService,
                modelDefinitionService,
                responsesModelClient,
                toolRegistry,
                agentToolExecutor,
                streamPublisher);
    }
}
