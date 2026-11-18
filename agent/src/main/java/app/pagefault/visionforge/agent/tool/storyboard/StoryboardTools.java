package app.pagefault.visionforge.agent.tool.storyboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.storyboard.StoryboardService;
import app.pagefault.visionforge.agent.storyboard.StoryboardSummary;
import app.pagefault.visionforge.agent.tool.AgentTool;
import app.pagefault.visionforge.agent.tool.ToolArguments;
import app.pagefault.visionforge.agent.tool.ToolDefinition;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.tool.ToolResult;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class StoryboardTools {

    private final StoryboardService storyboardService;
    private final ObjectMapper objectMapper;

    public List<AgentTool> all() {
        return List.of(new ValidateStoryboardTool());
    }

    private final class ValidateStoryboardTool implements AgentTool {

        private final ToolDefinition definition = new ToolDefinition(
                "validate_storyboard",
                "校验当前会话分镜 JSON 的 schema、镜头 id、顺序、提示词和引用字段；不写入。",
                readTree("""
                        {"type":"object","properties":{
                          "fileId":{"type":"string","format":"uuid"},
                          "version":{"type":"integer","minimum":1}
                        },"required":["fileId"],"additionalProperties":false}
                        """),
                false,
                60);

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            if (context.conversationId() == null) {
                throw new BusinessException(AgentErrorCode.TOOL_CONVERSATION_REQUIRED);
            }
            UUID fileId = ToolArguments.requiredUuid(arguments, "fileId");
            Integer version = ToolArguments.optionalInt(arguments, "version");
            StoryboardSummary summary = storyboardService.validateFile(
                    context.userId(), context.conversationId(), fileId, version);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", true);
            result.put("shotCount", summary.shotCount());
            result.put("firstOrder", summary.firstOrder());
            result.put("lastOrder", summary.lastOrder());
            return ToolResult.success(write(result));
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid tool schema", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not serialize tool result", exception);
        }
    }
}
