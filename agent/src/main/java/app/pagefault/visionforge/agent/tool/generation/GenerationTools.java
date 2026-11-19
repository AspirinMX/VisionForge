package app.pagefault.visionforge.agent.tool.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.generation.GenerationInputRole;
import app.pagefault.visionforge.agent.generation.GenerationOutput;
import app.pagefault.visionforge.agent.generation.GenerationStatus;
import app.pagefault.visionforge.agent.generation.GenerationSubmission;
import app.pagefault.visionforge.agent.generation.GenerationTask;
import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.generation.SubmitGenerationCommand;
import app.pagefault.visionforge.agent.generation.SubmitInput;
import app.pagefault.visionforge.agent.model.ModelCapabilityType;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.tool.AgentTool;
import app.pagefault.visionforge.agent.tool.ToolArguments;
import app.pagefault.visionforge.agent.tool.ToolDefinition;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.tool.ToolResult;
import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Media tools. They only accept or inspect tasks; a generated task continues after the AgentRun
 * ends and is never awaited inside the tool activity.
 */
@RequiredArgsConstructor
public class GenerationTools {

    private final GenerationTaskService taskService;
    private final ModelDefinitionService modelDefinitionService;
    private final AssetService assetService;
    private final ObjectMapper objectMapper;

    public List<AgentTool> all() {
        return List.of(
                new GenerateImageTool(),
                new GenerateVideoTool(),
                new AwaitGenerationTool(),
                new GetGenerationTool(),
                new CancelGenerationTool(),
                new InspectAssetTool());
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

    private final class GenerateImageTool extends BaseTool {

        private GenerateImageTool() {
            super(
                    "generate_image",
                    "创建图片生成任务。立即返回 taskId 和 ACCEPTED 状态；任务在后台继续，"
                            + "不要声称图片已经生成。",
                    """
                    {"type":"object","properties":{
                      "prompt":{"type":"string","maxLength":32000},
                      "modelDefinitionId":{"type":"string","format":"uuid"},
                      "parameters":{"type":"object"},
                      "inputs":{"type":"array","maxItems":8,"items":{
                        "type":"object","properties":{
                          "assetId":{"type":"string","format":"uuid"},
                          "fileVersionId":{"type":"string","format":"uuid","description":"可选；仅当明确获得已发布的 conversation_file_version ID 时填写；仅通过 assetId 引用图片时请省略此字段，切勿填入 assetId 或 fileId"},
                          "role":{"type":"string","enum":["REFERENCE","INIT_IMAGE","MASK"]}
                        },"required":["assetId"],"additionalProperties":false}},
                      "shotId":{"type":"string","maxLength":64},
                      "storyboardFileId":{"type":"string","format":"uuid"},
                      "storyboardVersion":{"type":"integer","minimum":1},
                      "sourceTaskId":{"type":"string","format":"uuid"}
                    },"required":["prompt"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            return submit(context, arguments, GenerationType.IMAGE);
        }
    }

    private final class GenerateVideoTool extends BaseTool {

        private GenerateVideoTool() {
            super(
                    "generate_video",
                    "创建视频生成任务。立即返回 taskId 和 ACCEPTED 状态；任务在后台继续，"
                            + "不要声称视频已经生成。",
                    """
                    {"type":"object","properties":{
                      "prompt":{"type":"string","maxLength":32000},
                      "modelDefinitionId":{"type":"string","format":"uuid"},
                      "parameters":{"type":"object"},
                      "inputs":{"type":"array","maxItems":8,"items":{
                        "type":"object","properties":{
                          "assetId":{"type":"string","format":"uuid"},
                          "fileVersionId":{"type":"string","format":"uuid","description":"可选；仅当明确获得已发布的 conversation_file_version ID 时填写；仅通过 assetId 引用图片时请省略此字段，切勿填入 assetId 或 fileId"},
                          "role":{"type":"string","enum":["FIRST_FRAME","LAST_FRAME","REFERENCE"]}
                        },"required":["assetId"],"additionalProperties":false}},
                      "shotId":{"type":"string","maxLength":64},
                      "storyboardFileId":{"type":"string","format":"uuid"},
                      "storyboardVersion":{"type":"integer","minimum":1},
                      "sourceTaskId":{"type":"string","format":"uuid"}
                    },"required":["prompt"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            return submit(context, arguments, GenerationType.VIDEO);
        }
    }

    private final class AwaitGenerationTool extends BaseTool {

        private AwaitGenerationTool() {
            super(
                    "await_generation",
                    "等待生成任务交付。任务未完成时工具进入持久化等待；完成后返回最终状态和产物，"
                            + "并把结果交回同一轮模型。",
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
            GenerationTask task = taskService.getForUser(context.userId(), taskId);
            if (taskService.isTerminal(taskId)) {
                return ToolResult.success(write(taskSummary(task)));
            }
            return ToolResult.waiting(taskId.toString());
        }
    }

    private final class GetGenerationTool extends BaseTool {

        private GetGenerationTool() {
            super(
                    "get_generation",
                    "查询生成任务的状态、输出资产和账务状态。",
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
            GenerationTask task = taskService.getForUser(context.userId(), taskId);
            return ToolResult.success(write(taskSummary(task)));
        }
    }

    private final class CancelGenerationTool extends BaseTool {

        private CancelGenerationTool() {
            super(
                    "cancel_generation",
                    "请求取消生成任务。已提交的任务可能无法取消，结果以任务状态为准。",
                    """
                    {"type":"object","properties":{
                      "taskId":{"type":"string","format":"uuid"}
                    },"required":["taskId"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID taskId = ToolArguments.requiredUuid(arguments, "taskId");
            return ToolResult.success(write(taskSummary(
                    taskService.cancel(context.userId(), taskId))));
        }
    }

    private final class InspectAssetTool extends BaseTool {

        private InspectAssetTool() {
            super(
                    "inspect_asset",
                    "读取当前会话素材的元数据。元数据不等于视觉理解；需要看图时请让用户在消息中附带该素材。",
                    """
                    {"type":"object","properties":{
                      "assetId":{"type":"string","format":"uuid"}
                    },"required":["assetId"],"additionalProperties":false}
                    """,
                    false);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID assetId = ToolArguments.requiredUuid(arguments, "assetId");
            Asset asset = assetService.getForConversation(
                    context.userId(), requireConversation(context), assetId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("assetId", asset.getId().toString());
            result.put("kind", asset.getKind());
            result.put("mimeType", asset.getMimeType());
            result.put("sizeBytes", asset.getSizeBytes());
            result.put("width", asset.getWidth());
            result.put("height", asset.getHeight());
            result.put("durationMs", asset.getDurationMs());
            result.put("status", asset.getStatus());
            result.put("visualContent", "metadata-only");
            return ToolResult.success(write(result));
        }
    }

    private ToolResult submit(
            ToolExecutionContext context, JsonNode arguments, GenerationType type) {
        UUID conversationId = requireConversation(context);
        String prompt = ToolArguments.requiredText(arguments, "prompt");
        UUID modelDefinitionId = ToolArguments.optionalUuid(arguments, "modelDefinitionId");
        if (modelDefinitionId == null) {
            ModelCapabilityType capability = type == GenerationType.IMAGE
                    ? ModelCapabilityType.IMAGE
                    : ModelCapabilityType.VIDEO;
            modelDefinitionId = modelDefinitionService
                    .requireDefaultEnabled(capability).getId();
        }
        String parameters = arguments.path("parameters").isMissingNode()
                || arguments.path("parameters").isNull()
                        ? "{}"
                        : arguments.path("parameters").toString();
        List<SubmitInput> inputs = parseInputs(arguments, type);
        UUID sourceTaskId = ToolArguments.optionalUuid(arguments, "sourceTaskId");
        String shotId = ToolArguments.optionalText(arguments, "shotId");
        UUID storyboardFileId = ToolArguments.optionalUuid(arguments, "storyboardFileId");
        Integer storyboardVersion = ToolArguments.optionalInt(arguments, "storyboardVersion");
        String idempotencyKey = "tool:" + context.agentRunId() + ':' + context.toolCallId();

        GenerationSubmission submission = taskService.submit(
                context.userId(),
                new SubmitGenerationCommand(
                        conversationId,
                        context.agentRunId(),
                        null,
                        type,
                        modelDefinitionId,
                        prompt,
                        parameters,
                        inputs,
                        idempotencyKey,
                        sourceTaskId,
                        shotId,
                        storyboardFileId,
                        storyboardVersion));
        Map<String, Object> result = taskSummary(submission.task());
        result.put("accepted", submission.task().getStatus() == GenerationStatus.PENDING);
        return ToolResult.success(write(result));
    }

    private List<SubmitInput> parseInputs(JsonNode arguments, GenerationType type) {
        JsonNode node = arguments.path("inputs");
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new BusinessException(
                    AgentErrorCode.TOOL_ARGUMENTS_INVALID, "inputs 必须是数组");
        }
        List<SubmitInput> inputs = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            String assetId = ToolArguments.requiredText(item, "assetId");
            UUID assetUuid;
            try {
                assetUuid = UUID.fromString(assetId);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(
                        AgentErrorCode.TOOL_ARGUMENTS_INVALID, "assetId 必须是 UUID");
            }
            UUID fileVersionId = ToolArguments.optionalUuid(item, "fileVersionId");
            GenerationInputRole role = ToolArguments.optionalEnum(
                    item, "role", GenerationInputRole.class);
            if (role != null && !roleAllowed(type, role)) {
                throw new BusinessException(
                        AgentErrorCode.TOOL_ARGUMENTS_INVALID,
                        "输入角色 " + role + " 不适用于 " + type);
            }
            inputs.add(new SubmitInput(assetUuid, fileVersionId, role));
        }
        return inputs;
    }

    private boolean roleAllowed(GenerationType type, GenerationInputRole role) {
        return switch (type) {
            case IMAGE -> role == GenerationInputRole.REFERENCE
                    || role == GenerationInputRole.INIT_IMAGE
                    || role == GenerationInputRole.MASK;
            case VIDEO -> role == GenerationInputRole.FIRST_FRAME
                    || role == GenerationInputRole.LAST_FRAME
                    || role == GenerationInputRole.REFERENCE;
        };
    }

    private Map<String, Object> taskSummary(GenerationTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId().toString());
        result.put("type", task.getType());
        result.put("status", task.getStatus());
        result.put("providerStatus", task.getProviderStatus());
        result.put("deliveryStatus", task.getDeliveryStatus());
        result.put("billingStatus", task.getBillingStatus());
        result.put("cancelStatus", task.getCancelStatus());
        result.put("modelDefinitionId", task.getModelDefinitionId().toString());
        result.put("priceUnits", task.getPriceUnits());
        result.put("createdAt", task.getCreatedAt());
        result.put("completedAt", task.getCompletedAt());
        result.put("outputs", taskService.outputs(task.getId()).stream()
                .map(this::outputSummary)
                .toList());
        return result;
    }

    private Map<String, Object> outputSummary(GenerationOutput output) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outputIndex", output.getOutputIndex());
        result.put("status", output.getStatus());
        result.put("assetId", output.getAssetId() == null
                ? null
                : output.getAssetId().toString());
        result.put("fileId", output.getFileId() == null
                ? null
                : output.getFileId().toString());
        result.put("fileVersionId", output.getFileVersionId() == null
                ? null
                : output.getFileVersionId().toString());
        result.put("errorMessage", output.getErrorMessage());
        return result;
    }

    private UUID requireConversation(ToolExecutionContext context) {
        if (context.conversationId() == null) {
            throw new BusinessException(AgentErrorCode.TOOL_CONVERSATION_REQUIRED);
        }
        return context.conversationId();
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
