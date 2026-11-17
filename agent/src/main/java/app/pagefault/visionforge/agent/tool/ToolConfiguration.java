package app.pagefault.visionforge.agent.tool;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.tool.files.ConversationFileTools;
import app.pagefault.visionforge.agent.tool.generation.GenerationTools;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ToolConfiguration {

    @Bean
    public ToolRegistry toolRegistry(
            ConversationFileService conversationFileService,
            GenerationTaskService generationTaskService,
            ModelDefinitionService modelDefinitionService,
            AssetService assetService,
            ObjectMapper objectMapper) {
        List<AgentTool> tools = new ArrayList<>(
                new ConversationFileTools(conversationFileService, objectMapper).all());
        tools.addAll(new GenerationTools(
                generationTaskService,
                modelDefinitionService,
                assetService,
                objectMapper).all());
        return new ToolRegistry(tools);
    }

    @Bean
    public AgentToolExecutor agentToolExecutor(
            ToolRegistry toolRegistry,
            ToolInvocationMapper toolInvocationMapper,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            Clock clock) {
        return new AgentToolExecutor(
                toolRegistry, toolInvocationMapper, objectMapper, idGenerator, clock);
    }
}
