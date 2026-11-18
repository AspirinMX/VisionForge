package app.pagefault.visionforge.agent.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ExecutionMode;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.agent.model.ModelCapabilityType;
import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.agent.provider.media.MediaProviderRegistry;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequest;
import app.pagefault.visionforge.agent.storyboard.StoryboardService;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequestMapper;
import app.pagefault.visionforge.agent.runtime.WorkflowStartType;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.billing.credit.CreditService;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;

class GenerationTaskServiceTest {

    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e34");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID MODEL_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e32");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private GenerationTaskMapper taskMapper;
    private GenerationOutputMapper outputMapper;
    private WorkflowStartRequestMapper startRequestMapper;
    private ModelDefinitionService modelDefinitionService;
    private CreditService creditService;
    private MediaProviderRegistry providerRegistry;
    private GenerationTaskService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), GenerationTask.class);
        taskMapper = mock(GenerationTaskMapper.class);
        GenerationAttemptMapper attemptMapper = mock(GenerationAttemptMapper.class);
        GenerationInputMapper inputMapper = mock(GenerationInputMapper.class);
        outputMapper = mock(GenerationOutputMapper.class);
        startRequestMapper = mock(WorkflowStartRequestMapper.class);
        modelDefinitionService = mock(ModelDefinitionService.class);
        ConversationReferenceChecker checker = mock(ConversationReferenceChecker.class);
        AssetService assetService = mock(AssetService.class);
        ConversationFileService fileService = mock(ConversationFileService.class);
        StoryboardService storyboardService = mock(StoryboardService.class);
        creditService = mock(CreditService.class);
        providerRegistry = mock(MediaProviderRegistry.class);
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new GenerationTaskService(
                taskMapper,
                attemptMapper,
                inputMapper,
                outputMapper,
                startRequestMapper,
                modelDefinitionService,
                checker,
                assetService,
                fileService,
                storyboardService,
                creditService,
                providerRegistry,
                new ObjectMapper(),
                idGenerator,
                clock);
    }

    @Test
    void submitCreatesTaskReservesCreditAndRequestsWorkflowStart() {
        when(modelDefinitionService.requireEnabled(MODEL_ID, ModelCapabilityType.IMAGE))
                .thenReturn(imageModel(100));

        GenerationSubmission submission = service.submit(USER_ID, command(null));

        GenerationTask task = submission.task();
        assertThat(task.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(task.getBillingStatus()).isEqualTo(GenerationBillingStatus.RESERVED);
        assertThat(task.getDeliveryStatus()).isEqualTo(GenerationDeliveryStatus.PENDING);
        assertThat(task.getPriceUnits()).isEqualTo(100);
        assertThat(task.getWorkflowId()).isEqualTo("media-generation-" + task.getId());
        assertThat(submission.created()).isTrue();

        verify(taskMapper).insert(task);
        verify(outputMapper).insert(any(GenerationOutput.class));
        verify(creditService).reserve(
                eq(USER_ID), eq("generation:" + task.getId()), eq(100L), eq(task.getId()));

        ArgumentCaptor<WorkflowStartRequest> startCaptor =
                ArgumentCaptor.forClass(WorkflowStartRequest.class);
        verify(startRequestMapper).insert(startCaptor.capture());
        assertThat(startCaptor.getValue().getWorkflowType())
                .isEqualTo(WorkflowStartType.MEDIA_GENERATION);
        assertThat(startCaptor.getValue().getBusinessId()).isEqualTo(task.getId());
    }

    @Test
    void sameIdempotencyKeyWithDifferentRequestIsRejected() {
        when(modelDefinitionService.requireEnabled(MODEL_ID, ModelCapabilityType.IMAGE))
                .thenReturn(imageModel(100));
        GenerationTask existing = new GenerationTask();
        existing.setId(UUID.randomUUID());
        existing.setUserId(USER_ID);
        existing.setRequestFingerprint("different");
        existing.setWorkflowId("media-generation-existing");
        when(taskMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service.submit(USER_ID, command("key-1")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AgentErrorCode.GENERATION_IDEMPOTENCY_CONFLICT));
    }

    private SubmitGenerationCommand command(String idempotencyKey) {
        return new SubmitGenerationCommand(
                CONVERSATION_ID,
                null,
                null,
                GenerationType.IMAGE,
                MODEL_ID,
                "a coffee poster",
                "{}",
                List.of(),
                idempotencyKey,
                null,
                null,
                null,
                null);
    }

    private ModelDefinition imageModel(long priceUnits) {
        ModelDefinition model = new ModelDefinition();
        model.setId(MODEL_ID);
        model.setModelId("stub-image");
        model.setCapabilityType(ModelCapabilityType.IMAGE);
        model.setProtocol(MediaProtocol.STUB);
        model.setExecutionMode(ExecutionMode.INLINE_RESULT);
        model.setPriceUnits(priceUnits);
        model.setEnabled(true);
        return model;
    }
}
