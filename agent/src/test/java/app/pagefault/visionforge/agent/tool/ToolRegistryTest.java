package app.pagefault.visionforge.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersToolsAndPreservesDefinitionOrder() {
        ToolRegistry registry = new ToolRegistry(List.of(
                tool("first"), tool("second")));

        assertThat(registry.definitions())
                .extracting(ToolDefinition::name)
                .containsExactly("first", "second");
        assertThat(registry.contains("first")).isTrue();
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("same"), tool("same"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownTool() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("known")));

        assertThatThrownBy(() -> registry.require("missing"))
                .satisfies(exception -> {
                    assertThat(exception).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(AgentErrorCode.TOOL_NOT_FOUND);
                });
    }

    private AgentTool tool(String name) {
        JsonNode schema = objectMapper.readTree("{\"type\":\"object\"}");
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test tool", schema, false, 30);
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
                return ToolResult.success("{}");
            }
        };
    }
}
