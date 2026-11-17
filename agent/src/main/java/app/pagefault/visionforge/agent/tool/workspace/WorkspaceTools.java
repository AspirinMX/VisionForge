package app.pagefault.visionforge.agent.tool.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.tool.AgentTool;
import app.pagefault.visionforge.agent.tool.ToolArguments;
import app.pagefault.visionforge.agent.tool.ToolDefinition;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.tool.ToolResult;
import app.pagefault.visionforge.asset.library.ConversationItemRole;
import app.pagefault.visionforge.asset.library.ConversationItemView;
import app.pagefault.visionforge.asset.library.CreateWorkspaceItemCommand;
import app.pagefault.visionforge.asset.library.UpdateWorkspaceItemCommand;
import app.pagefault.visionforge.asset.library.WorkspaceItem;
import app.pagefault.visionforge.asset.library.WorkspaceItemKind;
import app.pagefault.visionforge.asset.library.WorkspaceItemService;
import app.pagefault.visionforge.asset.library.WorkspaceItemSource;
import app.pagefault.visionforge.asset.library.WorkspaceItemVersion;
import app.pagefault.visionforge.asset.library.WorkspaceItemVisibility;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tools that let the model work with the workspace library. Items are always resolved through the
 * server-side execution context; the model only supplies item IDs, paths and content.
 */
@RequiredArgsConstructor
public class WorkspaceTools {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final WorkspaceItemService itemService;
    private final ObjectMapper objectMapper;

    public List<AgentTool> all() {
        return List.of(
                new ListItemsTool(),
                new SearchItemsTool(),
                new ReadItemTool(),
                new CreateItemTool(),
                new UpdateItemTool(),
                new ListVersionsTool(),
                new BindItemTool(),
                new UnbindItemTool());
    }

    private abstract class BaseTool implements AgentTool {

        private final ToolDefinition definition;

        private BaseTool(
                String name, String description, String schema, boolean sideEffect) {
            this.definition = new ToolDefinition(
                    name, description, readTree(schema), sideEffect, 60);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }
    }

    private final class ListItemsTool extends BaseTool {

        private ListItemsTool() {
            super(
                    "list_items",
                    "列出当前会话绑定的工作空间存储条目。需要会话上下文。",
                    """
                    {"type":"object","properties":{
                      "kind":{"type":"string","enum":["SCRIPT","STORYBOARD","PROMPT","IMAGE","VIDEO","OTHER"]},
                      "limit":{"type":"integer","minimum":1,"maximum":200}
                    },"additionalProperties":false}
                    """,
                    false);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID conversationId = requireConversation(context);
            WorkspaceItemKind kind = ToolArguments.optionalEnum(
                    arguments, "kind", WorkspaceItemKind.class);
            int limit = ToolArguments.limit(arguments, DEFAULT_LIMIT, MAX_LIMIT);
            List<ConversationItemView> views = itemService.listConversationItems(
                    context.workspaceId(),
                    context.userId(),
                    context.platformAdmin(),
                    conversationId,
                    MAX_LIMIT);
            List<Map<String, Object>> result = new ArrayList<>();
            for (ConversationItemView view : views) {
                if (kind != null && view.item().getKind() != kind) {
                    continue;
                }
                if (result.size() >= limit) {
                    break;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("item", itemSummary(view.item()));
                entry.put("role", view.binding().getRole());
                entry.put("pinnedVersion", view.binding().getPinnedVersion());
                result.add(entry);
            }
            return ToolResult.success(write(Map.of("items", result)));
        }
    }

    private final class SearchItemsTool extends BaseTool {

        private SearchItemsTool() {
            super(
                    "search_items",
                    "在工作空间存储库中按标题或路径搜索条目。",
                    """
                    {"type":"object","properties":{
                      "query":{"type":"string"},
                      "kind":{"type":"string","enum":["SCRIPT","STORYBOARD","PROMPT","IMAGE","VIDEO","OTHER"]},
                      "limit":{"type":"integer","minimum":1,"maximum":200}
                    },"additionalProperties":false}
                    """,
                    false);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            String query = ToolArguments.optionalText(arguments, "query");
            WorkspaceItemKind kind = ToolArguments.optionalEnum(
                    arguments, "kind", WorkspaceItemKind.class);
            int limit = ToolArguments.limit(arguments, DEFAULT_LIMIT, MAX_LIMIT);
            List<WorkspaceItem> items = itemService.search(
                    context.workspaceId(),
                    context.userId(),
                    context.platformAdmin(),
                    query,
                    kind,
                    limit);
            return ToolResult.success(write(Map.of(
                    "items", items.stream().map(WorkspaceTools.this::itemSummary).toList())));
        }
    }

    private final class ReadItemTool extends BaseTool {

        private ReadItemTool() {
            super(
                    "read_item",
                    "读取存储条目的元数据和文本内容，可指定历史版本。",
                    """
                    {"type":"object","properties":{
                      "itemId":{"type":"string","format":"uuid"},
                      "version":{"type":"integer","minimum":1}
                    },"required":["itemId"],"additionalProperties":false}
                    """,
                    false);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID itemId = ToolArguments.requiredUuid(arguments, "itemId");
            Integer version = ToolArguments.optionalInt(arguments, "version");
            WorkspaceItem item = itemService.get(
                    context.workspaceId(), context.userId(), context.platformAdmin(), itemId);
            WorkspaceItemVersion content = itemService.readVersion(
                    context.workspaceId(),
                    context.userId(),
                    context.platformAdmin(),
                    itemId,
                    version);
            Map<String, Object> result = itemSummary(item);
            result.put("version", content.getVersion());
            result.put("content", content.getContent());
            result.put("checksum", content.getChecksum());
            result.put("source", content.getSource());
            return ToolResult.success(write(result));
        }
    }

    private final class CreateItemTool extends BaseTool {

        private CreateItemTool() {
            super(
                    "create_item",
                    "在工作空间存储库创建文本条目，并自动绑定当前会话。",
                    """
                    {"type":"object","properties":{
                      "kind":{"type":"string","enum":["SCRIPT","STORYBOARD","PROMPT","OTHER"]},
                      "title":{"type":"string","maxLength":200},
                      "path":{"type":"string","maxLength":500},
                      "content":{"type":"string"},
                      "visibility":{"type":"string","enum":["PRIVATE","WORKSPACE"]},
                      "role":{"type":"string","enum":["WORKING","ATTACHMENT","REFERENCE","OUTPUT"]}
                    },"required":["kind","title","content"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID conversationId = requireConversation(context);
            WorkspaceItemKind kind = ToolArguments.optionalEnum(
                    arguments, "kind", WorkspaceItemKind.class);
            WorkspaceItem item = itemService.create(
                    context.workspaceId(),
                    context.userId(),
                    new CreateWorkspaceItemCommand(
                            kind,
                            ToolArguments.requiredText(arguments, "title"),
                            ToolArguments.optionalText(arguments, "path"),
                            ToolArguments.requiredText(arguments, "content"),
                            ToolArguments.optionalEnum(
                                    arguments, "visibility", WorkspaceItemVisibility.class),
                            conversationId,
                            ToolArguments.optionalEnum(
                                    arguments, "role", ConversationItemRole.class),
                            WorkspaceItemSource.AGENT));
            return ToolResult.success(write(Map.of("item", itemSummary(item))));
        }
    }

    private final class UpdateItemTool extends BaseTool {

        private UpdateItemTool() {
            super(
                    "update_item",
                    "基于 baseVersion 修改存储条目内容，成功后产生新版本。",
                    """
                    {"type":"object","properties":{
                      "itemId":{"type":"string","format":"uuid"},
                      "baseVersion":{"type":"integer","minimum":1},
                      "content":{"type":"string"},
                      "title":{"type":"string","maxLength":200}
                    },"required":["itemId","baseVersion","content"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID itemId = ToolArguments.requiredUuid(arguments, "itemId");
            Integer baseVersion = ToolArguments.optionalInt(arguments, "baseVersion");
            if (baseVersion == null) {
                throw new BusinessException(
                        AgentErrorCode.TOOL_ARGUMENTS_INVALID, "缺少参数 baseVersion");
            }
            WorkspaceItem item = itemService.update(
                    context.workspaceId(),
                    context.userId(),
                    context.platformAdmin(),
                    itemId,
                    new UpdateWorkspaceItemCommand(
                            baseVersion,
                            ToolArguments.requiredText(arguments, "content"),
                            ToolArguments.optionalText(arguments, "title"),
                            WorkspaceItemSource.AGENT));
            return ToolResult.success(write(Map.of("item", itemSummary(item))));
        }
    }

    private final class ListVersionsTool extends BaseTool {

        private ListVersionsTool() {
            super(
                    "list_item_versions",
                    "列出存储条目的版本历史。",
                    """
                    {"type":"object","properties":{
                      "itemId":{"type":"string","format":"uuid"},
                      "limit":{"type":"integer","minimum":1,"maximum":200}
                    },"required":["itemId"],"additionalProperties":false}
                    """,
                    false);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID itemId = ToolArguments.requiredUuid(arguments, "itemId");
            int limit = ToolArguments.limit(arguments, DEFAULT_LIMIT, MAX_LIMIT);
            List<WorkspaceItemVersion> versions = itemService.listVersions(
                    context.workspaceId(),
                    context.userId(),
                    context.platformAdmin(),
                    itemId,
                    limit);
            return ToolResult.success(write(Map.of(
                    "versions", versions.stream().map(WorkspaceTools.this::versionSummary).toList())));
        }
    }

    private final class BindItemTool extends BaseTool {

        private BindItemTool() {
            super(
                    "bind_item",
                    "把已有存储条目绑定到当前会话。",
                    """
                    {"type":"object","properties":{
                      "itemId":{"type":"string","format":"uuid"},
                      "role":{"type":"string","enum":["WORKING","ATTACHMENT","REFERENCE","OUTPUT"]},
                      "pinnedVersion":{"type":"integer","minimum":1}
                    },"required":["itemId"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID conversationId = requireConversation(context);
            UUID itemId = ToolArguments.requiredUuid(arguments, "itemId");
            ConversationItemRole role = ToolArguments.optionalEnum(
                    arguments, "role", ConversationItemRole.class);
            Integer pinnedVersion = ToolArguments.optionalInt(arguments, "pinnedVersion");
            itemService.bind(
                    context.workspaceId(),
                    context.userId(),
                    context.platformAdmin(),
                    conversationId,
                    itemId,
                    role,
                    pinnedVersion);
            return ToolResult.success(write(Map.of("bound", true, "itemId", itemId.toString())));
        }
    }

    private final class UnbindItemTool extends BaseTool {

        private UnbindItemTool() {
            super(
                    "unbind_item",
                    "解除当前会话与存储条目的绑定，不删除条目。",
                    """
                    {"type":"object","properties":{
                      "itemId":{"type":"string","format":"uuid"}
                    },"required":["itemId"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID conversationId = requireConversation(context);
            UUID itemId = ToolArguments.requiredUuid(arguments, "itemId");
            itemService.unbind(
                    context.workspaceId(),
                    context.userId(),
                    context.platformAdmin(),
                    conversationId,
                    itemId);
            return ToolResult.success(write(Map.of("unbound", true, "itemId", itemId.toString())));
        }
    }

    private UUID requireConversation(ToolExecutionContext context) {
        if (context.conversationId() == null) {
            throw new BusinessException(AgentErrorCode.TOOL_CONVERSATION_REQUIRED);
        }
        return context.conversationId();
    }

    private Map<String, Object> itemSummary(WorkspaceItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", item.getId().toString());
        result.put("kind", item.getKind());
        result.put("title", item.getTitle());
        result.put("path", item.getPath());
        result.put("mimeType", item.getMimeType());
        result.put("storageKind", item.getStorageKind());
        result.put("currentVersion", item.getCurrentVersion());
        result.put("status", item.getStatus());
        result.put("visibility", item.getVisibility());
        result.put("updatedAt", item.getUpdatedAt());
        return result;
    }

    private Map<String, Object> versionSummary(WorkspaceItemVersion version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version.getVersion());
        result.put("sizeBytes", version.getSizeBytes());
        result.put("checksum", version.getChecksum());
        result.put("source", version.getSource());
        result.put("createdAt", version.getCreatedAt());
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
