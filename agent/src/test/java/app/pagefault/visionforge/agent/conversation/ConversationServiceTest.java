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
import java.util.List;
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
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

class ConversationServiceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final UUID CONVERSATION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e34");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private ConversationMapper conversationMapper;
    private MessageMapper messageMapper;
    private MessagePartMapper messagePartMapper;
    private AgentRunMapper agentRunMapper;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        messagePartMapper = mock(MessagePartMapper.class);
        agentRunMapper = mock(AgentRunMapper.class);
        WorkflowStartRequestMapper startRequestMapper = mock(WorkflowStartRequestMapper.class);
        ModelDefinitionService modelDefinitionService = mock(ModelDefinitionService.class);
        AssetService assetService = mock(AssetService.class);
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ConversationService(
                conversationMapper,
                messageMapper,
                messagePartMapper,
                agentRunMapper,
                startRequestMapper,
                modelDefinitionService,
                assetService,
                idGenerator,
                clock);
    }

    @Test
    void rejectsTurnWhileAnotherRunIsActive() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(agentRunMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.startTurn(USER_ID, CONVERSATION_ID, "hello"))
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

        AgentTurnResult result = service.startTurn(USER_ID, CONVERSATION_ID, "hello");

        assertThat(result.runId()).isNotNull();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("hello");
        assertThat(messageCaptor.getValue().getSequenceNo()).isEqualTo(1);

        ArgumentCaptor<AgentRun> runCaptor = ArgumentCaptor.forClass(AgentRun.class);
        verify(agentRunMapper).insert(runCaptor.capture());
        assertThat(runCaptor.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void messagesWithPartsGroupsAttachmentsByMessageInOneQuery() {
        UUID messageId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(messageMapper.selectList(any())).thenReturn(List.of(message(messageId)));
        MessagePart assetPart = new MessagePart();
        assetPart.setMessageId(messageId);
        assetPart.setPartType(MessagePartType.ASSET_REFERENCE);
        assetPart.setOrdinal(0);
        assetPart.setAssetId(assetId);
        MessagePart taskPart = new MessagePart();
        taskPart.setMessageId(messageId);
        taskPart.setPartType(MessagePartType.GENERATION_TASK);
        taskPart.setOrdinal(1);
        taskPart.setGenerationTaskId(taskId);
        when(messagePartMapper.selectList(any())).thenReturn(List.of(assetPart, taskPart));

        List<MessageWithParts> result = service.messagesWithParts(USER_ID, CONVERSATION_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).assetIds()).containsExactly(assetId);
        assertThat(result.get(0).generationTaskIds()).containsExactly(taskId);
        verify(messagePartMapper).selectList(any());
    }

    @Test
    void messagesWithPartsSkipsThePartQueryForAnEmptyConversation() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(messageMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.messagesWithParts(USER_ID, CONVERSATION_ID)).isEmpty();
        verify(messagePartMapper, never()).selectList(any());
    }

    private Message message(UUID messageId) {
        Message message = new Message();
        message.setId(messageId);
        message.setConversationId(CONVERSATION_ID);
        message.setRole(MessageRole.USER);
        message.setContent("hello");
        message.setSequenceNo(1);
        message.setStatus(MessageStatus.COMPLETED);
        message.setCreatedAt(NOW);
        return message;
    }

    private Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setCreatedBy(USER_ID);
        conversation.setTitle("Chat");
        conversation.setCreatedAt(NOW);
        conversation.setUpdatedAt(NOW);
        return conversation;
    }
}
