package app.pagefault.visionforge.agent.tool;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.pagefault.visionforge.agent.tool.workspace.WorkspaceTools;
import app.pagefault.visionforge.asset.library.WorkspaceItemService;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ToolConfiguration {

    @Bean
    public ToolRegistry toolRegistry(
            WorkspaceItemService workspaceItemService, ObjectMapper objectMapper) {
        return new ToolRegistry(new WorkspaceTools(workspaceItemService, objectMapper).all());
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
