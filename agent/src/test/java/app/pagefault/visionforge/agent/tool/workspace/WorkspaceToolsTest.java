package app.pagefault.visionforge.agent.tool.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.tool.AgentTool;
import app.pagefault.visionforge.agent.tool.ToolExecutionContext;
import app.pagefault.visionforge.agent.tool.ToolRegistry;
import app.pagefault.visionforge.agent.tool.ToolResult;
import app.pagefault.visionforge.asset.library.CreateWorkspaceItemCommand;
import app.pagefault.visionforge.asset.library.WorkspaceItem;
import app.pagefault.visionforge.asset.library.WorkspaceItemKind;
import app.pagefault.visionforge.asset.library.WorkspaceItemService;
import app.pagefault.visionforge.asset.library.WorkspaceItemSource;
import app.pagefault.visionforge.asset.library.WorkspaceItemStatus;
import app.pagefault.visionforge.asset.library.WorkspaceItemStorageKind;
import app.pagefault.visionforge.asset.library.WorkspaceItemVisibility;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.ObjectMapper;

class WorkspaceToolsTest {

    private static final UUID WORKSPACE_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final UUID USER_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID RUN_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e31");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e32");

    private WorkspaceItemService itemService;
    private ObjectMapper objectMapper;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        itemService = mock(WorkspaceItemService.class);
        objectMapper = new ObjectMapper();
        registry = new ToolRegistry(new WorkspaceTools(itemService, objectMapper).all());
    }

    @Test
    void registersWorkspaceTools() {
        assertThat(registry.definitions())
                .extracting(definition -> definition.name())
                .containsExactly(
                        "list_items",
                        "search_items",
                        "read_item",
                        "create_item",
                        "update_item",
                        "list_item_versions",
                        "bind_item",
                        "unbind_item");
    }

    @Test
    void createItemUsesServerContextAndAgentSource() {
        WorkspaceItem item = item();
        when(itemService.create(any(), any(), any())).thenReturn(item);
        AgentTool tool = registry.require("create_item");

        ToolResult result = tool.execute(
                context(CONVERSATION_ID),
                objectMapper.readTree("""
                        {"kind":"SCRIPT","title":"Act 1","path":"scripts/act-1.md","content":"scene"}
                        """));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"itemId\"");
        ArgumentCaptor<UUID> workspaceCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> userCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<CreateWorkspaceItemCommand> commandCaptor =
                ArgumentCaptor.forClass(CreateWorkspaceItemCommand.class);
        verify(itemService).create(
                workspaceCaptor.capture(), userCaptor.capture(), commandCaptor.capture());
        assertThat(workspaceCaptor.getValue()).isEqualTo(WORKSPACE_ID);
        assertThat(userCaptor.getValue()).isEqualTo(USER_ID);
        CreateWorkspaceItemCommand command = commandCaptor.getValue();
        assertThat(command.kind()).isEqualTo(WorkspaceItemKind.SCRIPT);
        assertThat(command.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(command.source()).isEqualTo(WorkspaceItemSource.AGENT);
    }

    @Test
    void createItemRequiresConversation() {
        AgentTool tool = registry.require("create_item");

        assertThatThrownBy(() -> tool.execute(
                context(null),
                objectMapper.readTree("""
                        {"kind":"SCRIPT","title":"Act 1","content":"scene"}
                        """)))
                .satisfies(exception -> {
                    assertThat(exception).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(AgentErrorCode.TOOL_CONVERSATION_REQUIRED);
                });
    }

    private ToolExecutionContext context(UUID conversationId) {
        return new ToolExecutionContext(
                WORKSPACE_ID, conversationId, USER_ID, RUN_ID, "call-1", false);
    }

    private WorkspaceItem item() {
        WorkspaceItem item = new WorkspaceItem();
        item.setId(UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e40"));
        item.setWorkspaceId(WORKSPACE_ID);
        item.setKind(WorkspaceItemKind.SCRIPT);
        item.setTitle("Act 1");
        item.setPath("scripts/act-1.md");
        item.setMimeType("text/markdown");
        item.setStorageKind(WorkspaceItemStorageKind.DB_TEXT);
        item.setCurrentVersion(1);
        item.setStatus(WorkspaceItemStatus.DRAFT);
        item.setVisibility(WorkspaceItemVisibility.PRIVATE);
        item.setCreatedBy(USER_ID);
        item.setCreatedAt(Instant.parse("2026-11-18T12:00:00Z"));
        item.setUpdatedAt(Instant.parse("2026-11-18T12:00:00Z"));
        return item;
    }
}
