package app.pagefault.visionforge.agent.tool;

import tools.jackson.databind.JsonNode;

public interface AgentTool {

    ToolDefinition definition();

    ToolResult execute(ToolExecutionContext context, JsonNode arguments);
}
