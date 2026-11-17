package app.pagefault.visionforge.agent.tool;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.pagefault.visionforge.agent.tool.files.ConversationFileTools;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ToolConfiguration {

    @Bean
    public ToolRegistry toolRegistry(
            ConversationFileService conversationFileService, ObjectMapper objectMapper) {
        return new ToolRegistry(
                new ConversationFileTools(conversationFileService, objectMapper).all());
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
