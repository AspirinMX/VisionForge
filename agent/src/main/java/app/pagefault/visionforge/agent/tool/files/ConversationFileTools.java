package app.pagefault.visionforge.agent.tool.files;

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
import app.pagefault.visionforge.asset.library.ConversationFile;
import app.pagefault.visionforge.asset.library.ConversationFileKind;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationFileSource;
import app.pagefault.visionforge.asset.library.ConversationFileVersion;
import app.pagefault.visionforge.asset.library.CreateConversationFileCommand;
import app.pagefault.visionforge.asset.library.UpdateConversationFileCommand;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tools that let the model work with the files of the current conversation. The conversation and
 * user always come from the server-side execution context; the model only supplies file IDs, paths
 * and content.
 */
@RequiredArgsConstructor
public class ConversationFileTools {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ConversationFileService fileService;
    private final ObjectMapper objectMapper;

    public List<AgentTool> all() {
        return List.of(
                new ListFilesTool(),
                new SearchFilesTool(),
                new ReadFileTool(),
                new CreateFileTool(),
                new UpdateFileTool(),
                new ListVersionsTool());
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

    private final class ListFilesTool extends BaseTool {

        private ListFilesTool() {
            super(
                    "list_files",
                    "列出当前会话工作区中的文件。",
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
            ConversationFileKind kind = ToolArguments.optionalEnum(
                    arguments, "kind", ConversationFileKind.class);
            int limit = ToolArguments.limit(arguments, DEFAULT_LIMIT, MAX_LIMIT);
            List<ConversationFile> files = fileService.list(
                    context.userId(), requireConversation(context), kind, limit);
            return ToolResult.success(write(Map.of(
                    "files", files.stream().map(ConversationFileTools.this::fileSummary).toList())));
        }
    }

    private final class SearchFilesTool extends BaseTool {

        private SearchFilesTool() {
            super(
                    "search_files",
                    "在当前会话工作区中按标题或路径搜索文件。",
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
            ConversationFileKind kind = ToolArguments.optionalEnum(
                    arguments, "kind", ConversationFileKind.class);
            int limit = ToolArguments.limit(arguments, DEFAULT_LIMIT, MAX_LIMIT);
            List<ConversationFile> files = fileService.search(
                    context.userId(), requireConversation(context), query, kind, limit);
            return ToolResult.success(write(Map.of(
                    "files", files.stream().map(ConversationFileTools.this::fileSummary).toList())));
        }
    }

    private final class ReadFileTool extends BaseTool {

        private ReadFileTool() {
            super(
                    "read_file",
                    "读取文件元数据和文本内容，可指定历史版本。",
                    """
                    {"type":"object","properties":{
                      "fileId":{"type":"string","format":"uuid"},
                      "version":{"type":"integer","minimum":1}
                    },"required":["fileId"],"additionalProperties":false}
                    """,
                    false);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID fileId = ToolArguments.requiredUuid(arguments, "fileId");
            Integer version = ToolArguments.optionalInt(arguments, "version");
            ConversationFile file = fileService.get(
                    context.userId(), requireConversation(context), fileId);
            ConversationFileVersion content = fileService.readVersion(
                    context.userId(), requireConversation(context), fileId, version);
            Map<String, Object> result = fileSummary(file);
            result.put("version", content.getVersion());
            result.put("content", content.getContent());
            result.put("checksum", content.getChecksum());
            result.put("source", content.getSource());
            return ToolResult.success(write(result));
        }
    }

    private final class CreateFileTool extends BaseTool {

        private CreateFileTool() {
            super(
                    "create_file",
                    "在当前会话工作区创建文本文件。",
                    """
                    {"type":"object","properties":{
                      "kind":{"type":"string","enum":["SCRIPT","STORYBOARD","PROMPT","OTHER"]},
                      "title":{"type":"string","maxLength":200},
                      "path":{"type":"string","maxLength":500},
                      "content":{"type":"string"}
                    },"required":["kind","title","content"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            ConversationFile file = fileService.create(
                    context.userId(),
                    requireConversation(context),
                    new CreateConversationFileCommand(
                            ToolArguments.optionalEnum(
                                    arguments, "kind", ConversationFileKind.class),
                            ToolArguments.requiredText(arguments, "title"),
                            ToolArguments.optionalText(arguments, "path"),
                            ToolArguments.requiredText(arguments, "content"),
                            ConversationFileSource.AGENT));
            return ToolResult.success(write(Map.of("file", fileSummary(file))));
        }
    }

    private final class UpdateFileTool extends BaseTool {

        private UpdateFileTool() {
            super(
                    "update_file",
                    "基于 baseVersion 修改文件内容，成功后产生新版本。",
                    """
                    {"type":"object","properties":{
                      "fileId":{"type":"string","format":"uuid"},
                      "baseVersion":{"type":"integer","minimum":1},
                      "content":{"type":"string"},
                      "title":{"type":"string","maxLength":200}
                    },"required":["fileId","baseVersion","content"],"additionalProperties":false}
                    """,
                    true);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID fileId = ToolArguments.requiredUuid(arguments, "fileId");
            Integer baseVersion = ToolArguments.optionalInt(arguments, "baseVersion");
            if (baseVersion == null) {
                throw new BusinessException(
                        AgentErrorCode.TOOL_ARGUMENTS_INVALID, "缺少参数 baseVersion");
            }
            ConversationFile file = fileService.update(
                    context.userId(),
                    requireConversation(context),
                    fileId,
                    new UpdateConversationFileCommand(
                            baseVersion,
                            ToolArguments.requiredText(arguments, "content"),
                            ToolArguments.optionalText(arguments, "title"),
                            ConversationFileSource.AGENT));
            return ToolResult.success(write(Map.of("file", fileSummary(file))));
        }
    }

    private final class ListVersionsTool extends BaseTool {

        private ListVersionsTool() {
            super(
                    "list_file_versions",
                    "列出文件的版本历史。",
                    """
                    {"type":"object","properties":{
                      "fileId":{"type":"string","format":"uuid"},
                      "limit":{"type":"integer","minimum":1,"maximum":200}
                    },"required":["fileId"],"additionalProperties":false}
                    """,
                    false);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            UUID fileId = ToolArguments.requiredUuid(arguments, "fileId");
            int limit = ToolArguments.limit(arguments, DEFAULT_LIMIT, MAX_LIMIT);
            List<ConversationFileVersion> versions = fileService.listVersions(
                    context.userId(), requireConversation(context), fileId, limit);
            return ToolResult.success(write(Map.of(
                    "versions",
                    versions.stream().map(ConversationFileTools.this::versionSummary).toList())));
        }
    }

    private UUID requireConversation(ToolExecutionContext context) {
        if (context.conversationId() == null) {
            throw new BusinessException(AgentErrorCode.TOOL_CONVERSATION_REQUIRED);
        }
        return context.conversationId();
    }

    private Map<String, Object> fileSummary(ConversationFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", file.getId().toString());
        result.put("kind", file.getKind());
        result.put("title", file.getTitle());
        result.put("path", file.getPath());
        result.put("mimeType", file.getMimeType());
        result.put("storageKind", file.getStorageKind());
        result.put("currentVersion", file.getCurrentVersion());
        result.put("status", file.getStatus());
        result.put("updatedAt", file.getUpdatedAt());
        return result;
    }

    private Map<String, Object> versionSummary(ConversationFileVersion version) {
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
