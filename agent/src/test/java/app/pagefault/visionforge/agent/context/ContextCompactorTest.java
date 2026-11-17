package app.pagefault.visionforge.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.pagefault.visionforge.agent.conversation.Message;
import app.pagefault.visionforge.agent.conversation.MessageRole;
import app.pagefault.visionforge.agent.conversation.MessageStatus;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.model.ModelProviderSettings;
import app.pagefault.visionforge.agent.model.ReasoningEffort;
import app.pagefault.visionforge.agent.provider.ModelRequest;
import app.pagefault.visionforge.agent.provider.ModelResponse;
import app.pagefault.visionforge.agent.provider.ResponsesModelClient;
import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.ModelInvocationPurpose;
import app.pagefault.visionforge.agent.runtime.ModelInvocationRecorder;
import app.pagefault.visionforge.agent.runtime.ModelInvocationStatus;
import app.pagefault.visionforge.common.id.IdGenerator;

class ContextCompactorTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final UUID CONVERSATION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID RUN_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e31");
    private static final UUID MODEL_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e32");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private ContextSummaryMapper summaryMapper;
    private ModelDefinitionService modelDefinitionService;
    private ResponsesModelClient modelClient;
    private ModelInvocationRecorder invocationRecorder;
    private ContextCompactor compactor;

    @BeforeEach
    void setUp() {
        summaryMapper = mock(ContextSummaryMapper.class);
        modelDefinitionService = mock(ModelDefinitionService.class);
        modelClient = mock(ResponsesModelClient.class);
        invocationRecorder = mock(ModelInvocationRecorder.class);
        IdGenerator idGenerator = UUID::randomUUID;
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        compactor = new ContextCompactor(
                summaryMapper,
                modelDefinitionService,
                modelClient,
                invocationRecorder,
                idGenerator,
                clock,
                0.8,
                0.25,
                1_024);
        when(modelDefinitionService.settings(any())).thenReturn(new ModelProviderSettings(
                MODEL_ID,
                "https://api.example.com/v1",
                "key",
                "model",
                ReasoningEffort.NONE,
                1_000,
                100));
    }

    @Test
    void keepsHistoryWhenItStillFits() {
        List<Message> history = messages(5, 10);

        ContextSummary summary = compactor.ensureWithinBudget(run(), model(2_000), history, 0);

        assertThat(summary).isNull();
        verify(modelClient, never()).stream(any(), any());
    }

    @Test
    void summarizesOldestRangeAndPublishesNewEpoch() {
        List<Message> history = messages(5, 40);
        when(modelClient.stream(any(), any())).thenReturn(response("SUMMARY"));

        ContextSummary summary = compactor.ensureWithinBudget(run(), model(200), history, 10);

        assertThat(summary).isNotNull();
        assertThat(summary.getEpoch()).isEqualTo(1);
        assertThat(summary.getFromSeq()).isEqualTo(1);
        assertThat(summary.getToSeq()).isEqualTo(4);
        assertThat(summary.getSourceDigest()).isNotBlank();
        assertThat(summary.getPromptVersion()).isEqualTo("compact-v1");

        ArgumentCaptor<ContextSummary> captor = ArgumentCaptor.forClass(ContextSummary.class);
        verify(summaryMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("SUMMARY");

        ArgumentCaptor<ModelRequest> requestCaptor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelClient).stream(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().tools()).isEmpty();
        assertThat(requestCaptor.getValue().modelId()).isEqualTo("model");
    }

    @Test
    void fallsBackToHistoryWhenSummaryCallFails() {
        List<Message> history = messages(5, 40);
        when(modelClient.stream(any(), any())).thenThrow(new RuntimeException("upstream down"));

        ContextSummary summary = compactor.ensureWithinBudget(run(), model(200), history, 10);

        assertThat(summary).isNull();
        verify(summaryMapper, never()).insert(any(ContextSummary.class));
        verify(invocationRecorder).record(
                RUN_ID,
                MODEL_ID,
                ModelInvocationPurpose.COMPACT,
                ModelInvocationStatus.FAILED,
                null,
                "upstream down");
    }

    private ModelResponse response(String text) {
        return new ModelResponse("resp_1", text, List.of(), 10, 5, "completed", 20);
    }

    private AgentRun run() {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setCreatedAt(NOW);
        return run;
    }

    private ModelDefinition model(int maxInputTokens) {
        ModelDefinition model = new ModelDefinition();
        model.setId(MODEL_ID);
        model.setDisplayName("Mock");
        model.setBaseUrl("https://api.example.com/v1");
        model.setModelId("model");
        model.setMaxInputTokens(maxInputTokens);
        model.setMaxOutputTokens(100);
        model.setEnabled(true);
        return model;
    }

    private List<Message> messages(int count, int contentLength) {
        List<Message> messages = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            Message message = new Message();
            message.setId(UUID.randomUUID());
            message.setConversationId(CONVERSATION_ID);
            message.setWorkspaceId(WORKSPACE_ID);
            message.setRole(index % 2 == 0 ? MessageRole.ASSISTANT : MessageRole.USER);
            message.setContent("x".repeat(contentLength));
            message.setSequenceNo(index);
            message.setStatus(MessageStatus.COMPLETED);
            message.setCreatedAt(NOW);
            messages.add(message);
        }
        return messages;
    }
}
