package app.pagefault.visionforge.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AgentToolExecutorTest {

    private static final UUID WORKSPACE_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final UUID USER_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID RUN_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e31");
    private static final String CALL_ID = "call-1";
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private ToolInvocationMapper invocationMapper;
    private AgentToolExecutor executor;
    private MutableTool tool;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ToolInvocation.class);
        invocationMapper = mock(ToolInvocationMapper.class);
        tool = new MutableTool();
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        executor = new AgentToolExecutor(
                new ToolRegistry(List.of(tool)),
                invocationMapper,
                new ObjectMapper(),
                idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void executesAndRecordsOnce() {
        tool.result = ToolResult.success("{\"ok\":true}");

        ToolResult result = executor.execute(context(), "mutable", "{}");

        assertThat(result).isEqualTo(tool.result);
        verify(invocationMapper).insert(any(ToolInvocation.class));
        verify(invocationMapper).updateById(any(ToolInvocation.class));
        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    void replaysCompletedInvocationWithoutExecutingToolAgain() {
        ToolInvocation existing = invocation(ToolInvocationStatus.SUCCEEDED);
        existing.setResult("{\"cached\":true}");
        when(invocationMapper.selectOne(any())).thenReturn(existing);

        ToolResult result = executor.execute(context(), "mutable", "{}");

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("{\"cached\":true}");
        assertThat(tool.calls).isZero();
        verify(invocationMapper, never()).insert(any(ToolInvocation.class));
    }

    @Test
    void rejectsInvocationThatIsStillRunning() {
        when(invocationMapper.selectOne(any()))
                .thenReturn(invocation(ToolInvocationStatus.RUNNING));

        assertThatThrownBy(() -> executor.execute(context(), "mutable", "{}"))
                .satisfies(exception -> {
                    assertThat(exception).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(AgentErrorCode.TOOL_INVOCATION_IN_PROGRESS);
                });
        assertThat(tool.calls).isZero();
    }

    @Test
    void returnsBusinessFailureAsStructuredResult() {
        tool.failure = new BusinessException(AgentErrorCode.TOOL_ARGUMENTS_INVALID, "bad");

        ToolResult result = executor.execute(context(), "mutable", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(AgentErrorCode.TOOL_ARGUMENTS_INVALID.code());
        assertThat(result.modelContent()).contains("bad");
        verify(invocationMapper).updateById(any(ToolInvocation.class));
    }

    @Test
    void rejectsInvalidJsonArguments() {
        assertThatThrownBy(() -> executor.execute(context(), "mutable", "not-json"))
                .satisfies(exception -> {
                    assertThat(exception).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(AgentErrorCode.TOOL_ARGUMENTS_INVALID);
                });
        assertThat(tool.calls).isZero();
    }

    @Test
    void returnsUnknownToolAsStructuredFailure() {
        ToolResult result = executor.execute(context(), "missing", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(AgentErrorCode.TOOL_NOT_FOUND.code());
        verify(invocationMapper, never()).insert(any(ToolInvocation.class));
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(
                WORKSPACE_ID, null, USER_ID, RUN_ID, CALL_ID, false);
    }

    private ToolInvocation invocation(ToolInvocationStatus status) {
        ToolInvocation invocation = new ToolInvocation();
        invocation.setId(UUID.randomUUID());
        invocation.setWorkspaceId(WORKSPACE_ID);
        invocation.setAgentRunId(RUN_ID);
        invocation.setToolCallId(CALL_ID);
        invocation.setToolName("mutable");
        invocation.setArguments("{}");
        invocation.setStatus(status);
        invocation.setCreatedAt(NOW);
        return invocation;
    }

    private final class MutableTool implements AgentTool {

        private final JsonNode schema = new ObjectMapper().readTree("{\"type\":\"object\"}");
        private ToolResult result = ToolResult.success("{}");
        private RuntimeException failure;
        private int calls;

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("mutable", "mutable test tool", schema, true, 30);
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
