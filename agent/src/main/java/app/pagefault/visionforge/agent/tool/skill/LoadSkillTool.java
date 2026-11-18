package app.pagefault.visionforge.agent.tool.skill;

import app.pagefault.visionforge.agent.skill.SkillRegistry;
import app.pagefault.visionforge.agent.tool.AgentTool;
import app.pagefault.visionforge.agent.tool.ToolArguments;
import app.pagefault.visionforge.agent.tool.ToolDefinition;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.tool.ToolResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class LoadSkillTool implements AgentTool {

    private final SkillRegistry registry;
    private final ToolDefinition definition;

    public LoadSkillTool(SkillRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.definition = new ToolDefinition(
                "load_skill",
                "读取已注册的创作 skill 指南。name 必须使用系统提示列出的名称。",
                objectMapper.readTree("""
                        {"type":"object","properties":{"name":{"type":"string","maxLength":64}},
                         "required":["name"],"additionalProperties":false}
                        """),
                false,
                60);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
        return ToolResult.success(registry.require(ToolArguments.requiredText(arguments, "name")).instructions());
    }
}
