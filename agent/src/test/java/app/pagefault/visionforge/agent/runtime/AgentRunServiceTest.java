package app.pagefault.visionforge.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.pagefault.visionforge.agent.conversation.Conversation;
import app.pagefault.visionforge.agent.conversation.ConversationMapper;
import app.pagefault.visionforge.agent.conversation.Message;
import app.pagefault.visionforge.agent.conversation.MessageMapper;
import app.pagefault.visionforge.agent.conversation.MessagePart;
import app.pagefault.visionforge.agent.conversation.MessagePartMapper;
import app.pagefault.visionforge.agent.conversation.MessagePartType;
import app.pagefault.visionforge.agent.conversation.MessageRole;
import app.pagefault.visionforge.agent.conversation.MessageStatus;
import app.pagefault.visionforge.agent.context.ContextCompactor;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.model.ReasoningReplayPolicy;
import app.pagefault.visionforge.agent.provider.ModelInputItem;
import app.pagefault.visionforge.agent.skill.SkillRegistry;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentPrompt;
import app.pagefault.visionforge.agent.tool.ToolInvocation;
import app.pagefault.visionforge.agent.tool.ToolInvocationMapper;
import app.pagefault.visionforge.agent.tool.ToolInvocationStatus;
import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetKind;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;

class AgentRunServiceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final UUID CONVERSATION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID RUN_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e31");
    private static final UUID MODEL_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e32");
    private static final UUID USER_MESSAGE_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e33");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private AgentRunMapper agentRunMapper;
    private AgentRunTurnMapper agentRunTurnMapper;
    private ToolInvocationMapper toolInvocationMapper;
    private MessageMapper messageMapper;
    private MessagePartMapper messagePartMapper;
    private AssetService assetService;
    private WorkflowStartRequestMapper workflowStartRequestMapper;
    private ConversationMapper conversationMapper;
    private ModelDefinitionService modelDefinitionService;
    private ContextCompactor contextCompactor;
    private AgentRunService service;

    @BeforeEach
    void setUp() throws Exception {
        agentRunMapper = mock(AgentRunMapper.class);
        ModelInvocationRecorder invocationRecorder = mock(ModelInvocationRecorder.class);
        agentRunTurnMapper = mock(AgentRunTurnMapper.class);
        toolInvocationMapper = mock(ToolInvocationMapper.class);
        workflowStartRequestMapper = mock(WorkflowStartRequestMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        messagePartMapper = mock(MessagePartMapper.class);
        assetService = mock(AssetService.class);
        modelDefinitionService = mock(ModelDefinitionService.class);
        contextCompactor = mock(ContextCompactor.class);
        when(contextCompactor.ensureWithinBudget(any(), any(), any(), anyInt())).thenReturn(null);
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AgentRunService(
                agentRunMapper,
                invocationRecorder,
                agentRunTurnMapper,
                toolInvocationMapper,
                workflowStartRequestMapper,
                conversationMapper,
                messageMapper,
                messagePartMapper,
                assetService,
                modelDefinitionService,
                contextCompactor,
                new SkillRegistry(),
                new ObjectMapper(),
                idGenerator,
                clock);
    }

    @Test
    void loadPromptResolvesModelAndMarksRunRunning() {
        AgentRun run = run(AgentRunStatus.PENDING);
        Conversation conversation = conversation();
        ModelDefinition model = model();

        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);
        when(modelDefinitionService.resolveForRun(null)).thenReturn(model);

        AgentPrompt prompt = service.loadPrompt(RUN_ID);

        assertThat(prompt.runId()).isEqualTo(RUN_ID);
        assertThat(prompt.modelDefinitionId()).isEqualTo(MODEL_ID);
        assertThat(prompt.maxInputTokens()).isEqualTo(100_000);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.getModelDefinitionId()).isEqualTo(MODEL_ID);
        verify(agentRunMapper).updateById(run);
    }

    @Test
    void buildInputReplaysHistoryTurnAndToolOutput() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        run.setModelDefinitionId(MODEL_ID);
        Message user = message(MessageRole.USER, "Hello", 1);
        user.setId(USER_MESSAGE_ID);
        run.setUserMessageId(USER_MESSAGE_ID);

        AgentRunTurn turn = new AgentRunTurn();
        turn.setAgentRunId(RUN_ID);
        turn.setTurnNo(0);
        turn.setAssistantText("checking");
        turn.setFunctionCalls(
                "[{\"callId\":\"call_1\",\"name\":\"read_item\",\"arguments\":\"{}\"}]");

        ToolInvocation invocation = new ToolInvocation();
        invocation.setStatus(ToolInvocationStatus.SUCCEEDED);
        invocation.setResult("{\"ok\":true}");

        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(modelDefinitionService.get(MODEL_ID)).thenReturn(model());
        when(messageMapper.selectById(USER_MESSAGE_ID)).thenReturn(user);
        when(agentRunTurnMapper.selectList(any())).thenReturn(List.of(turn));
        when(toolInvocationMapper.selectOne(any())).thenReturn(invocation);
        when(messageMapper.selectList(any())).thenReturn(List.of(user));

        AgentModelInput input = service.buildInput(RUN_ID);

        assertThat(input.input()).containsExactly(
                new ModelInputItem.Message("user", "Hello"),
                new ModelInputItem.Message("assistant", "checking"),
                new ModelInputItem.FunctionCall("call_1", "read_item", "{}"),
                new ModelInputItem.FunctionCallOutput("call_1", "{\"ok\":true}"));
    }

    @Test
    void attachesCurrentMessageImagesAsInputImage() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        run.setModelDefinitionId(MODEL_ID);
        Message user = message(MessageRole.USER, "Hello", 1);
        user.setId(USER_MESSAGE_ID);
        run.setUserMessageId(USER_MESSAGE_ID);
        UUID assetId = UUID.randomUUID();
        MessagePart part = new MessagePart();
        part.setId(UUID.randomUUID());
        part.setMessageId(USER_MESSAGE_ID);
        part.setPartType(MessagePartType.ASSET_REFERENCE);
        part.setAssetId(assetId);
        part.setOrdinal(0);

        ModelDefinition model = model();
        model.setMaxInputTokens(100_000);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(modelDefinitionService.get(MODEL_ID)).thenReturn(model);
        when(messageMapper.selectById(USER_MESSAGE_ID)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user));
        when(messagePartMapper.selectList(any())).thenReturn(List.of(part));
        when(assetService.get(WORKSPACE_ID, assetId)).thenReturn(
                asset(assetId, AssetKind.IMAGE, "image/png", 3));
        when(assetService.readBytes(WORKSPACE_ID, assetId, 10L * 1024 * 1024))
                .thenReturn(new byte[] {1, 2, 3});

        AgentModelInput input = service.buildInput(RUN_ID);

        assertThat(input.input()).contains(new ModelInputItem.UserMessage(
                "Hello",
                List.of(new ModelInputItem.ImageInput(
                        "data:image/png;base64,AQID", "auto"))));
    }

    @Test
    void skipsNonImageAttachmentsAndRejectsOversizedImages() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        run.setModelDefinitionId(MODEL_ID);
        Message user = message(MessageRole.USER, "Hello", 1);
        user.setId(USER_MESSAGE_ID);
        run.setUserMessageId(USER_MESSAGE_ID);
        UUID videoId = UUID.randomUUID();
        MessagePart part = new MessagePart();
        part.setId(UUID.randomUUID());
        part.setMessageId(USER_MESSAGE_ID);
        part.setPartType(MessagePartType.ASSET_REFERENCE);
        part.setAssetId(videoId);
        part.setOrdinal(0);

        ModelDefinition model = model();
        model.setMaxInputTokens(100_000);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(modelDefinitionService.get(MODEL_ID)).thenReturn(model);
        when(messageMapper.selectById(USER_MESSAGE_ID)).thenReturn(user);
        when(messageMapper.selectList(any())).thenReturn(List.of(user));
        when(messagePartMapper.selectList(any())).thenReturn(List.of(part));
        when(assetService.get(WORKSPACE_ID, videoId)).thenReturn(
                asset(videoId, AssetKind.VIDEO, "video/mp4", 1024));

        AgentModelInput input = service.buildInput(RUN_ID);

        assertThat(input.input()).contains(new ModelInputItem.UserMessage(
                "Hello", List.of()));

        UUID hugeImageId = UUID.randomUUID();
        part.setAssetId(hugeImageId);
        when(assetService.get(WORKSPACE_ID, hugeImageId)).thenReturn(
                asset(hugeImageId, AssetKind.IMAGE, "image/png", 11L * 1024 * 1024));

        assertThatThrownBy(() -> service.buildInput(RUN_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单张图片不能超过 10 MB");
    }

    @Test
    void replaysEncryptedReasoningFromTheCurrentEpoch() {
        AgentModelInput input = buildInputWithReasoning(0, 0);

        assertThat(input.input()).contains(new ModelInputItem.Reasoning(
                "gAAA", null, List.of("plan")));
    }

    @Test
    void skipsReasoningProducedBeforeCompaction() {
        AgentModelInput input = buildInputWithReasoning(1, 2);

        assertThat(input.input())
                .noneMatch(item -> item instanceof ModelInputItem.Reasoning);
    }

    private AgentModelInput buildInputWithReasoning(int turnEpoch, int currentEpoch) {
        AgentRun run = run(AgentRunStatus.RUNNING);
        run.setModelDefinitionId(MODEL_ID);
        Message user = message(MessageRole.USER, "Hello", 1);
        user.setId(USER_MESSAGE_ID);
        run.setUserMessageId(USER_MESSAGE_ID);

        AgentRunTurn turn = new AgentRunTurn();
        turn.setAgentRunId(RUN_ID);
        turn.setTurnNo(0);
        turn.setAssistantText("checking");
        turn.setReasoning(
                "[{\"encryptedContent\":\"gAAA\",\"text\":null,\"summary\":[\"plan\"]}]");
        turn.setContextEpoch(turnEpoch);

        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(modelDefinitionService.get(MODEL_ID)).thenReturn(model());
        when(messageMapper.selectById(USER_MESSAGE_ID)).thenReturn(user);
        when(agentRunTurnMapper.selectList(any())).thenReturn(List.of(turn));
        when(messageMapper.selectList(any())).thenReturn(List.of(user));
        when(contextCompactor.currentEpoch(CONVERSATION_ID)).thenReturn(currentEpoch);
        return service.buildInput(RUN_ID);
    }

    @Test
    void completePersistsAssistantMessageFromFinalTurn() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        Conversation conversation = conversation();
        AgentRunTurn turn = new AgentRunTurn();
        turn.setAgentRunId(RUN_ID);
        turn.setTurnNo(1);
        turn.setAssistantText("Reply");

        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);
        when(messageMapper.selectOne(any())).thenReturn(message(MessageRole.USER, "Hello", 1));
        when(agentRunTurnMapper.selectOne(any())).thenReturn(turn);

        service.complete(RUN_ID, 1);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        Message assistant = captor.getValue();
        assertThat(assistant.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(assistant.getContent()).isEqualTo("Reply");
        assertThat(assistant.getSequenceNo()).isEqualTo(2);
        assertThat(assistant.getStatus()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.getAssistantMessageId()).isEqualTo(assistant.getId());
    }

    @Test
    void failMarksRunAsFailedWithoutAssistantMessage() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);

        service.fail(RUN_ID, "provider down");

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.getErrorMessage()).isEqualTo("provider down");
        verify(agentRunMapper).updateById(run);
    }

    @Test
    void startFailureStaysPendingBeforeMaxAttempts() {
        WorkflowStartRequest request = startRequest(0);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run(AgentRunStatus.PENDING));

        service.recordWorkflowStartFailure(request, "temporal down", 3);

        assertThat(request.getStatus()).isEqualTo(WorkflowStartStatus.PENDING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getLastError()).isEqualTo("temporal down");
        verify(workflowStartRequestMapper).updateById(request);
    }

    @Test
    void startFailureFailsRunAtMaxAttempts() {
        WorkflowStartRequest request = startRequest(2);
        AgentRun run = run(AgentRunStatus.PENDING);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);

        service.recordWorkflowStartFailure(request, "temporal down", 3);

        assertThat(request.getStatus()).isEqualTo(WorkflowStartStatus.FAILED);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("temporal down");
        verify(workflowStartRequestMapper).updateById(request);
    }

    private WorkflowStartRequest startRequest(int attempts) {
        WorkflowStartRequest request = new WorkflowStartRequest();
        request.setId(UUID.randomUUID());
        request.setAgentRunId(RUN_ID);
        request.setWorkflowId("agent-run-" + RUN_ID);
        request.setStatus(WorkflowStartStatus.PENDING);
        request.setAttempts(attempts);
        request.setCreatedAt(NOW);
        request.setUpdatedAt(NOW);
        return request;
    }

    @Test
    void listReturnsUserScopedRuns() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        when(agentRunMapper.selectList(any())).thenReturn(List.of(run));

        List<AgentRun> result = service.list(
                WORKSPACE_ID, CONVERSATION_ID, List.of(AgentRunStatus.RUNNING), 10);

        assertThat(result).containsExactly(run);
        verify(agentRunMapper).selectList(any());
    }

    @Test
    void cancelMarksPendingOrRunningAsCancelledAndClosesStartRequest() {
        AgentRun run = run(AgentRunStatus.PENDING);
        WorkflowStartRequest pendingStart = startRequest(0);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(workflowStartRequestMapper.selectOne(any())).thenReturn(pendingStart);

        AgentRun cancelled = service.cancel(WORKSPACE_ID, RUN_ID);

        assertThat(cancelled.getStatus()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(cancelled.getCompletedAt()).isEqualTo(NOW);
        assertThat(pendingStart.getStatus()).isEqualTo(WorkflowStartStatus.FAILED);
        verify(workflowStartRequestMapper).updateById(pendingStart);

        // Late failure callback must not overwrite CANCELLED.
        assertThat(service.fail(RUN_ID, "late error")).isFalse();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.CANCELLED);
    }

    private AgentRun run(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId(WORKSPACE_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setUserMessageId(UUID.randomUUID());
        run.setStatus(status);
        run.setWorkflowId("agent-run-" + RUN_ID);
        run.setCreatedAt(NOW);
        return run;
    }

    private Asset asset(UUID id, AssetKind kind, String mimeType, long sizeBytes) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setKind(kind);
        asset.setMimeType(mimeType);
        asset.setSizeBytes(sizeBytes);
        return asset;
    }

    private Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setCreatedBy(WORKSPACE_ID);
        conversation.setTitle("Chat");
        conversation.setCreatedAt(NOW);
        conversation.setUpdatedAt(NOW);
        return conversation;
    }

    private ModelDefinition model() {
        ModelDefinition model = new ModelDefinition();
        model.setId(MODEL_ID);
        model.setDisplayName("Mock");
        model.setBaseUrl("https://api.example.com/v1");
        model.setModelId("mock-model");
        model.setReasoningReplay(ReasoningReplayPolicy.ENCRYPTED);
        model.setMaxInputTokens(100_000);
        model.setMaxOutputTokens(100);
        model.setEnabled(true);
        return model;
    }

    private Message message(MessageRole role, String content, long sequence) {
        Message message = new Message();
        message.setId(UUID.randomUUID());
        message.setConversationId(CONVERSATION_ID);
        message.setRole(role);
        message.setContent(content);
        message.setSequenceNo(sequence);
        message.setStatus(MessageStatus.COMPLETED);
        message.setCreatedAt(NOW);
        return message;
    }
}
