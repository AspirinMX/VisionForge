package app.pagefault.visionforge.agent.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.agent.runtime.ToolInvocationSummary;
import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AgentRunStreamEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deltaOmitsNullOptionalFields() throws Exception {
        String json = objectMapper.writeValueAsString(AgentRunStreamEvent.delta("你好"));
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.path("type").asText()).isEqualTo("delta");
        assertThat(node.path("content").asText()).isEqualTo("你好");
        assertThat(node.has("callId")).isFalse();
        assertThat(node.has("turnNo")).isFalse();
    }

    @Test
    void toolEventsCarryStructuredActionTargetAndLinks() throws Exception {
        UUID fileId = UUID.randomUUID();
        ToolInvocationSummary summary = new ToolInvocationSummary(
                "call_1",
                "create_file",
                "创建文件",
                "script.md",
                ToolInvocationStatus.SUCCEEDED,
                fileId,
                null,
                null,
                null,
                null);

        String json = objectMapper.writeValueAsString(AgentRunStreamEvent.toolDone(0, summary));
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.path("type").asText()).isEqualTo("tool_done");
        assertThat(node.path("turnNo").asInt()).isEqualTo(0);
        assertThat(node.path("callId").asText()).isEqualTo("call_1");
        assertThat(node.path("action").asText()).isEqualTo("创建文件");
        assertThat(node.path("target").asText()).isEqualTo("script.md");
        assertThat(node.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(node.path("fileId").asText()).isEqualTo(fileId.toString());
    }
}
