package app.pagefault.visionforge.agent.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> registered = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            String name = tool.definition().name();
            if (registered.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("Duplicate tool name: " + name);
            }
        }
        this.tools = Collections.unmodifiableMap(registered);
    }

    public AgentTool require(String name) {
        AgentTool tool = name == null ? null : tools.get(name);
        if (tool == null) {
            throw new BusinessException(AgentErrorCode.TOOL_NOT_FOUND);
        }
        return tool;
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public boolean contains(String name) {
        return name != null && tools.containsKey(name);
    }
}
