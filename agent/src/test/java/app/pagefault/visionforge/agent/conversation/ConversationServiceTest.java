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
import app.pagefault.visionforge.agent.runtime.AgentProcessReader;
import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.AgentRunMapper;
import app.pagefault.visionforge.agent.runtime.AgentRunProcessView;
import app.pagefault.visionforge.agent.runtime.AgentTurnProcessView;
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
    private WorkflowStartRequestMapper startRequestMapper;
    private ModelDefinitionService modelDefinitionService;
    private AssetService assetService;
    private IdGenerator idGenerator;
    private Clock clock;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        messagePartMapper = mock(MessagePartMapper.class);
        agentRunMapper = mock(AgentRunMapper.class);
        startRequestMapper = mock(WorkflowStartRequestMapper.class);
        modelDefinitionService = mock(ModelDefinitionService.class);
        assetService = mock(AssetService.class);
        AtomicLong sequence = new AtomicLong();
        idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
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
    void reusesBlankConversationWhenWorkspaceIsEmpty() {
        Conversation blank = conversation();
        when(conversationMapper.findReusableBlank(USER_ID)).thenReturn(blank);

        Conversation result = service.createOrReuseBlank(USER_ID);

        assertThat(result).isSameAs(blank);
        verify(conversationMapper).lockUser(USER_ID);
        verify(conversationMapper, never()).insert(any(Conversation.class));
    }

    @Test
    void createsBlankConversationWhenNoReusableWorkspaceExists() {
        when(conversationMapper.findReusableBlank(USER_ID)).thenReturn(null);

        Conversation result = service.createOrReuseBlank(USER_ID);

        assertThat(result.getTitle()).isEqualTo("新对话");
        assertThat(result.getCreatedBy()).isEqualTo(USER_ID);
        verify(conversationMapper).insert(result);
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

    @Test
    void messagesWithPartsAttachesPersistedProcessForAssistantMessages() {
        AgentProcessReader processReader = mock(AgentProcessReader.class);
        ConversationService withProcess = new ConversationService(
                conversationMapper,
                messageMapper,
                messagePartMapper,
                agentRunMapper,
                startRequestMapper,
                modelDefinitionService,
                assetService,
                processReader,
                idGenerator,
                clock);

        UUID assistantMessageId = UUID.randomUUID();
        Message assistantMessage = message(assistantMessageId);
        assistantMessage.setRole(MessageRole.ASSISTANT);
        AgentRunProcessView expectedProcess = new AgentRunProcessView(
                UUID.randomUUID(),
                12_000L,
                List.of(new AgentTurnProcessView(0, "分析思路", null, List.of())));

        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(messageMapper.selectList(any())).thenReturn(List.of(assistantMessage));
        when(messagePartMapper.selectList(any())).thenReturn(List.of());
        when(processReader.byAssistantMessageIds(List.of(assistantMessageId)))
                .thenReturn(java.util.Map.of(assistantMessageId, expectedProcess));

        List<MessageWithParts> result =
                withProcess.messagesWithParts(USER_ID, CONVERSATION_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).process()).isEqualTo(expectedProcess);
    }

    @Test
    void renameUpdatesTitleAndDeleteSoftDeletesWhenIdle() {
        Conversation conv = conversation();
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conv);
        when(agentRunMapper.selectCount(any())).thenReturn(0L);

        Conversation renamed = service.rename(USER_ID, CONVERSATION_ID, "  Short Film Cut  ");
        assertThat(renamed.getTitle()).isEqualTo("Short Film Cut");
        verify(conversationMapper).updateById(conv);

        service.delete(USER_ID, CONVERSATION_ID);
        assertThat(conv.getDeletedAt()).isEqualTo(NOW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(USER_ID, CONVERSATION_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteRejectsActiveRun() {
        Conversation conv = conversation();
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conv);
        when(agentRunMapper.selectCount(any())).thenReturn(1L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(USER_ID, CONVERSATION_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AgentErrorCode.CONVERSATION_BUSY));
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
