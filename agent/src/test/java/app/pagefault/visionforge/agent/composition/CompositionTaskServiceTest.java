package app.pagefault.visionforge.agent.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.generation.GenerationTaskMapper;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequestMapper;
import app.pagefault.visionforge.agent.storyboard.StoryboardService;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;

class CompositionTaskServiceTest {

    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e34");
    private static final UUID CONVERSATION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private CompositionTaskMapper taskMapper;
    private CompositionInputMapper inputMapper;
    private ConversationReferenceChecker conversationChecker;
    private CompositionTaskService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(CompositionTaskMapper.class);
        inputMapper = mock(CompositionInputMapper.class);
        conversationChecker = mock(ConversationReferenceChecker.class);
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        service = new CompositionTaskService(
                taskMapper,
                inputMapper,
                mock(WorkflowStartRequestMapper.class),
                conversationChecker,
                mock(StoryboardService.class),
                mock(GenerationTaskMapper.class),
                mock(AssetService.class),
                new ObjectMapper(),
                idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listVerifiesConversationOwnershipBeforeQuerying() {
        doThrow(new BusinessException(AgentErrorCode.CONVERSATION_NOT_FOUND))
                .when(conversationChecker).requireOwned(USER_ID, CONVERSATION_ID);

        assertThatThrownBy(() -> service.list(
                USER_ID, CONVERSATION_ID, CompositionStatus.COMPOSING, 10))
                .isInstanceOf(BusinessException.class);
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    void listReturnsTheMapperRows() {
        CompositionTask task = new CompositionTask();
        task.setId(UUID.randomUUID());
        task.setUserId(USER_ID);
        task.setConversationId(CONVERSATION_ID);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        assertThat(service.list(USER_ID, CONVERSATION_ID, null, 10))
                .containsExactly(task);
        verify(conversationChecker).requireOwned(USER_ID, CONVERSATION_ID);
    }

    @Test
    void inputsByTaskIdsSkipsTheQueryForAnEmptyBatch() {
        assertThat(service.inputsByTaskIds(List.of())).isEmpty();
        assertThat(service.inputsByTaskIds(null)).isEmpty();
        verify(inputMapper, never()).selectList(any());
    }
}
