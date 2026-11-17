package app.pagefault.visionforge.agent.conversation;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.AgentRunMapper;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequestMapper;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

class ConversationServiceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final UUID CONVERSATION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e34");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private ConversationMapper conversationMapper;
    private MessageMapper messageMapper;
    private AgentRunMapper agentRunMapper;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        agentRunMapper = mock(AgentRunMapper.class);
        WorkflowStartRequestMapper startRequestMapper = mock(WorkflowStartRequestMapper.class);
        ModelDefinitionService modelDefinitionService = mock(ModelDefinitionService.class);
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ConversationService(
                conversationMapper,
                messageMapper,
                agentRunMapper,
                startRequestMapper,
                modelDefinitionService,
                idGenerator,
                clock);
    }

    @Test
    void rejectsTurnWhileAnotherRunIsActive() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(agentRunMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.startTurn(
                WORKSPACE_ID, CONVERSATION_ID, USER_ID, false, "hello"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AgentErrorCode.CONVERSATION_BUSY));

        verify(conversationMapper).selectForUpdate(CONVERSATION_ID);
        verify(messageMapper, never()).insert(any(Message.class));
        verify(agentRunMapper, never()).insert(any(AgentRun.class));
    }

    @Test
    void createsMessageAndRunWhenConversationIsIdle() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(agentRunMapper.selectCount(any())).thenReturn(0L);
        when(messageMapper.selectOne(any())).thenReturn(null);

        AgentTurnResult result = service.startTurn(
                WORKSPACE_ID, CONVERSATION_ID, USER_ID, false, "hello");

        assertThat(result.runId()).isNotNull();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("hello");
        assertThat(messageCaptor.getValue().getSequenceNo()).isEqualTo(1);

        ArgumentCaptor<AgentRun> runCaptor = ArgumentCaptor.forClass(AgentRun.class);
        verify(agentRunMapper).insert(runCaptor.capture());
        assertThat(runCaptor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(runCaptor.getValue().isPlatformAdmin()).isFalse();
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
}
