package app.pagefault.visionforge.agent.tool;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

import app.pagefault.visionforge.agent.composition.CompositionTaskService;
import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.skill.SkillRegistry;
import app.pagefault.visionforge.agent.storyboard.StoryboardService;
import app.pagefault.visionforge.agent.storyboard.StoryboardValidator;
import app.pagefault.visionforge.agent.tool.composition.CompositionTools;
import app.pagefault.visionforge.agent.tool.files.ConversationFileTools;
import app.pagefault.visionforge.agent.tool.generation.GenerationTools;
import app.pagefault.visionforge.agent.tool.skill.LoadSkillTool;
import app.pagefault.visionforge.agent.tool.storyboard.StoryboardTools;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ToolConfiguration {

    @Bean
    public SkillRegistry skillRegistry(ResourcePatternResolver resolver) throws IOException {
        return new SkillRegistry(resolver.getResources("classpath*:visionforge/skills/*/SKILL.md"));
    }

    @Bean
    public ToolRegistry toolRegistry(
            ConversationFileService conversationFileService,
            GenerationTaskService generationTaskService,
            CompositionTaskService compositionTaskService,
            ModelDefinitionService modelDefinitionService,
            AssetService assetService,
            StoryboardService storyboardService,
            StoryboardValidator storyboardValidator,
            SkillRegistry skillRegistry,
            ObjectMapper objectMapper) {
        List<AgentTool> tools = new ArrayList<>(
                new ConversationFileTools(
                        conversationFileService, storyboardValidator, objectMapper).all());
        tools.addAll(new GenerationTools(
                generationTaskService,
                modelDefinitionService,
                assetService,
                objectMapper).all());
        tools.addAll(new StoryboardTools(storyboardService, objectMapper).all());
        tools.addAll(new CompositionTools(compositionTaskService, objectMapper).all());
        tools.add(new LoadSkillTool(skillRegistry, objectMapper));
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
