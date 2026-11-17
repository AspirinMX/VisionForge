package app.pagefault.visionforge.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import app.pagefault.visionforge.agent.conversation.MessageRole;
import app.pagefault.visionforge.agent.conversation.MessageStatus;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.temporal.agentrun.AgentPrompt;
import app.pagefault.visionforge.agent.temporal.agentrun.ModelReply;
import app.pagefault.visionforge.common.id.IdGenerator;

class AgentRunServiceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final UUID CONVERSATION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID RUN_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e31");
    private static final UUID MODEL_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e32");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private AgentRunMapper agentRunMapper;
    private MessageMapper messageMapper;
    private WorkflowStartRequestMapper workflowStartRequestMapper;
    private ConversationMapper conversationMapper;
    private ModelDefinitionService modelDefinitionService;
    private AgentRunService service;

    @BeforeEach
    void setUp() {
        agentRunMapper = mock(AgentRunMapper.class);
        ModelInvocationMapper modelInvocationMapper = mock(ModelInvocationMapper.class);
        workflowStartRequestMapper = mock(WorkflowStartRequestMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        modelDefinitionService = mock(ModelDefinitionService.class);
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AgentRunService(
                agentRunMapper,
                modelInvocationMapper,
                workflowStartRequestMapper,
                conversationMapper,
                messageMapper,
                modelDefinitionService,
                idGenerator,
                clock);
    }

    @Test
    void loadPromptBuildsSystemAndHistoryTurns() {
        AgentRun run = run(AgentRunStatus.PENDING);
        Conversation conversation = conversation();
        ModelDefinition model = model();
        Message user = message(MessageRole.USER, "Hello", 1);
        Message assistant = message(MessageRole.ASSISTANT, "Hi", 2);

        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);
        when(modelDefinitionService.resolveForRun(null)).thenReturn(model);
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));

        AgentPrompt prompt = service.loadPrompt(RUN_ID);

        assertThat(prompt.turns()).hasSize(3);
        assertThat(prompt.turns().get(0).role()).isEqualTo("system");
        assertThat(prompt.turns().get(1).content()).isEqualTo("Hello");
        assertThat(prompt.turns().get(2).content()).isEqualTo("Hi");
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(run.getModelDefinitionId()).isEqualTo(MODEL_ID);
        verify(agentRunMapper).updateById(run);
    }

    @Test
    void completePersistsAssistantMessageAndFinishesRun() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        Conversation conversation = conversation();
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation);
        when(messageMapper.selectOne(any())).thenReturn(message(MessageRole.USER, "Hello", 1));

        service.complete(RUN_ID, new ModelReply("Reply", 10, 5, "stop", 20));

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

    private AgentRun run(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setUserMessageId(UUID.randomUUID());
        run.setStatus(status);
        run.setWorkflowId("agent-run-" + RUN_ID);
        run.setCreatedAt(NOW);
        return run;
    }

    private Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setWorkspaceId(WORKSPACE_ID);
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
        model.setMaxInputTokens(1_000);
        model.setMaxOutputTokens(100);
        model.setEnabled(true);
        return model;
    }

    private Message message(MessageRole role, String content, long sequence) {
        Message message = new Message();
        message.setId(UUID.randomUUID());
        message.setConversationId(CONVERSATION_ID);
        message.setWorkspaceId(WORKSPACE_ID);
        message.setRole(role);
        message.setContent(content);
        message.setSequenceNo(sequence);
        message.setStatus(MessageStatus.COMPLETED);
        message.setCreatedAt(NOW);
        return message;
    }
}
