package app.pagefault.visionforge.agent.tool.composition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.pagefault.visionforge.agent.composition.CompositionSubmission;
import app.pagefault.visionforge.agent.composition.CompositionTask;
import app.pagefault.visionforge.agent.composition.CompositionTaskService;
import app.pagefault.visionforge.agent.composition.SubmitCompositionCommand;
import app.pagefault.visionforge.agent.error.AgentErrorCode;
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
public class CompositionTools {

    private final CompositionTaskService taskService;
    private final ObjectMapper objectMapper;

    public List<AgentTool> all() {
        return List.of(new ComposeVideoTool(), new GetCompositionTool());
    }

    private abstract class BaseTool implements AgentTool {

        private final ToolDefinition definition;

        private BaseTool(String name, String description, String schema, boolean sideEffect) {
            this.definition = new ToolDefinition(
                    name, description, readTree(schema), sideEffect, 60);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }
    }

    private final class ComposeVideoTool extends BaseTool {

        private ComposeVideoTool() {
            super(
                    "compose_video",
                    "按分镜顺序读取所选视频片段，裁剪、统一规格并拼接为成片。立即返回成片任务 ID，"
                            + "任务在后台继续；不要声称成片已经生成。",
                    """
                    {"type":"object","properties":{
                      "storyboardFileId":{"type":"string","format":"uuid"},
                      "storyboardVersion":{"type":"integer","minimum":1},
                      "outputSpec":{"type":"object"}
                    },"required":["storyboardFileId"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            if (context.conversationId() == null) {
                throw new BusinessException(AgentErrorCode.TOOL_CONVERSATION_REQUIRED);
            }
            UUID fileId = ToolArguments.requiredUuid(arguments, "storyboardFileId");
            Integer version = ToolArguments.optionalInt(arguments, "storyboardVersion");
            String outputSpec = arguments.path("outputSpec").isMissingNode()
                    || arguments.path("outputSpec").isNull()
                            ? null
                            : arguments.path("outputSpec").toString();
            CompositionSubmission submission = taskService.submit(
                    context.userId(),
                    new SubmitCompositionCommand(
                            context.conversationId(), fileId, version, outputSpec));
            return ToolResult.success(write(summary(submission.task())));
        }
    }

    private final class GetCompositionTool extends BaseTool {

        private GetCompositionTool() {
            super(
                    "get_composition",
                    "查询成片任务状态、所用镜头和成片引用。",
                    """
                    {"type":"object","properties":{
                      "taskId":{"type":"string","format":"uuid"}
                    },"required":["taskId"],"additionalProperties":false}
                    """,
                    false);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID taskId = ToolArguments.requiredUuid(arguments, "taskId");
            return ToolResult.success(write(summary(
                    taskService.getForUser(context.userId(), taskId))));
        }
    }

    private Map<String, Object> summary(CompositionTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId().toString());
        result.put("status", task.getStatus());
        result.put("storyboardFileId", task.getStoryboardFileId().toString());
        result.put("storyboardVersion", task.getStoryboardVersion());
        result.put("outputAssetId", task.getOutputAssetId() == null
                ? null
                : task.getOutputAssetId().toString());
        result.put("outputFileId", task.getOutputFileId() == null
                ? null
                : task.getOutputFileId().toString());
        result.put("errorMessage", task.getErrorMessage());
        return result;
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
