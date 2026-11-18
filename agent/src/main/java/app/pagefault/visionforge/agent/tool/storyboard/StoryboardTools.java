package app.pagefault.visionforge.agent.tool.storyboard;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.storyboard.StoryboardAspectRatio;
import app.pagefault.visionforge.agent.storyboard.StoryboardCamera;
import app.pagefault.visionforge.agent.storyboard.StoryboardService;
import app.pagefault.visionforge.agent.storyboard.StoryboardShotSize;
import app.pagefault.visionforge.agent.storyboard.StoryboardShotStatus;
import app.pagefault.visionforge.agent.storyboard.StoryboardSummary;
import app.pagefault.visionforge.agent.tool.AgentTool;
import app.pagefault.visionforge.agent.tool.ToolArguments;
import app.pagefault.visionforge.agent.tool.ToolDefinition;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.tool.ToolResult;
import app.pagefault.visionforge.asset.library.ConversationFile;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class StoryboardTools {

    private final StoryboardService storyboardService;
    private final ObjectMapper objectMapper;

    public List<AgentTool> all() {
        return List.of(new SaveStoryboardTool(), new ValidateStoryboardTool());
    }

    private final class SaveStoryboardTool implements AgentTool {

        private final ToolDefinition definition = new ToolDefinition(
                "save_storyboard",
                "保存结构化短片分镜。创建时给出 storyboard 和可选 path，更新时同时给出 fileId 和 baseVersion。",
                readTree("""
                        {"type":"object","properties":{
                          "fileId":{"type":"string","format":"uuid"},
                          "baseVersion":{"type":"integer","minimum":1},
                          "path":{"type":"string","maxLength":500},
                          "storyboard":{"type":"object","properties":{
                            "schemaVersion":{"type":"integer","enum":[1]},
                            "title":{"type":"string","minLength":1,"maxLength":200},
                            "aspectRatio":{"type":"string","pattern":"^[1-9][0-9]{0,3}:[1-9][0-9]{0,3}$"},
                            "language":{"type":"string"},"style":{"type":"string"},
                            "shots":{"type":"array","minItems":1,"maxItems":200,"items":{
                              "type":"object","properties":{
                                "id":{"type":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},
                                "order":{"type":"integer","minimum":1},
                                "sceneId":{"type":"string"},
                                "durationSeconds":{"type":"integer","minimum":1,"maximum":60},
                                "visual":{"type":"string"},
                                "firstFramePrompt":{"type":"string","minLength":1,"maxLength":8000},
                                "videoPrompt":{"type":"string","minLength":1,"maxLength":8000},
                                "shotSize":{"type":"string","enum":%s},
                                "camera":{"type":"string","enum":%s},
                                "dialogue":{"type":"string"},"voiceOver":{"type":"string"},"sound":{"type":"string"},
                                "references":{"type":"array","items":{"type":"object"}},
                                "firstFrame":{"type":["object","null"]},
                                "videoCandidates":{"type":"array","items":{"type":"object"}},
                                "selectedVideo":{"type":["object","null"]},
                                "usedRange":{"type":["object","null"],"properties":{
                                  "startSeconds":{"type":"number","minimum":0},"endSeconds":{"type":"number","exclusiveMinimum":0}},
                                  "required":["startSeconds","endSeconds"],"additionalProperties":false},
                                "status":{"type":"string","enum":%s}
                              },"required":["id","order","durationSeconds","firstFramePrompt","videoPrompt","shotSize","camera","status"],
                              "additionalProperties":false
                            }}
                          },"required":["schemaVersion","title","aspectRatio","shots"],"additionalProperties":false}
                        },"required":["storyboard"],"additionalProperties":false}
                        """.formatted(enumNames(StoryboardShotSize.values()),
                        enumNames(StoryboardCamera.values()), enumNames(StoryboardShotStatus.values()))),
                true,
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
            JsonNode storyboard = arguments.path("storyboard");
            if (!storyboard.isObject()) {
                throw new BusinessException(AgentErrorCode.TOOL_ARGUMENTS_INVALID, "storyboard 必须是对象");
            }
            if (StoryboardAspectRatio.parse(storyboard.path("aspectRatio").asText("")) == null) {
                throw new BusinessException(AgentErrorCode.STORYBOARD_INVALID, "aspectRatio 必须使用 16:9 等宽高比格式");
            }
            ConversationFile file = storyboardService.saveDraft(
                    context.userId(), context.conversationId(),
                    ToolArguments.optionalUuid(arguments, "fileId"),
                    ToolArguments.optionalInt(arguments, "baseVersion"),
                    ToolArguments.optionalText(arguments, "path"), storyboard);
            return ToolResult.success(write(Map.of(
                    "fileId", file.getId(), "version", file.getCurrentVersion(), "title", file.getTitle())));
        }
    }

    private String enumNames(Enum<?>[] values) {
        return write(Arrays.stream(values).map(Enum::name).toList());
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
