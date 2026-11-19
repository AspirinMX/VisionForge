package app.pagefault.visionforge.agent.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import app.pagefault.visionforge.agent.storyboard.StoryboardDocument;
import app.pagefault.visionforge.agent.storyboard.StoryboardService;
import app.pagefault.visionforge.agent.storyboard.StoryboardShot;
import app.pagefault.visionforge.agent.runtime.WorkflowStartRequestMapper;
import app.pagefault.visionforge.agent.runtime.WorkflowStartType;
import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetKind;
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
    private GenerationInputMapper inputMapper;
    private GenerationOutputMapper outputMapper;
    private WorkflowStartRequestMapper startRequestMapper;
    private ModelDefinitionService modelDefinitionService;
    private AssetService assetService;
    private ConversationFileService fileService;
    private CreditService creditService;
    private MediaProviderRegistry providerRegistry;
    private GenerationTaskService service;
    private StoryboardService storyboardService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), GenerationTask.class);
        taskMapper = mock(GenerationTaskMapper.class);
        GenerationAttemptMapper attemptMapper = mock(GenerationAttemptMapper.class);
        inputMapper = mock(GenerationInputMapper.class);
        outputMapper = mock(GenerationOutputMapper.class);
        startRequestMapper = mock(WorkflowStartRequestMapper.class);
        modelDefinitionService = mock(ModelDefinitionService.class);
        ConversationReferenceChecker checker = mock(ConversationReferenceChecker.class);
        assetService = mock(AssetService.class);
        fileService = mock(ConversationFileService.class);
        storyboardService = mock(StoryboardService.class);
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
    void linkedVideoFreezesStoryboardDurationAndRatio() {
        ModelDefinition model = imageModel(0);
        model.setProtocol(MediaProtocol.DASHSCOPE_VIDEO);
        model.setExecutionMode(ExecutionMode.REMOTE_TASK);
        when(modelDefinitionService.requireEnabled(MODEL_ID, ModelCapabilityType.VIDEO)).thenReturn(model);
        UUID storyboardId = UUID.randomUUID();
        StoryboardShot shot = mock(StoryboardShot.class);
        when(shot.durationSeconds()).thenReturn(5);
        StoryboardDocument document = new StoryboardDocument(1, "Test", "16:9", null, null, List.of(shot));
        when(storyboardService.resolveShot(USER_ID, CONVERSATION_ID, storyboardId, null, "shot-1"))
                .thenReturn(new StoryboardService.ResolvedShot(shot, 3, document));

        GenerationTask task = service.submit(USER_ID, new SubmitGenerationCommand(
                CONVERSATION_ID, null, null, GenerationType.VIDEO, MODEL_ID, "Camera pushes in",
                """
                {"duration":2,"ratio":"1:1","resolution":"720P"}
                """,
                List.of(), null, null, "shot-1", storyboardId, null)).task();

        assertThat(task.getStoryboardVersion()).isEqualTo(3);
        var parameters = new ObjectMapper().readTree(task.getParameters());
        assertThat(parameters.path("durationSeconds").asInt()).isEqualTo(5);
        assertThat(parameters.path("ratio").asText()).isEqualTo("16:9");
        assertThat(parameters.path("resolution").asText()).isEqualTo("720P");
        assertThat(parameters.has("duration")).isFalse();
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

    @Test
    void rejectsNonExistentFileVersionIdBeforeInsert() {
        when(modelDefinitionService.requireEnabled(MODEL_ID, ModelCapabilityType.VIDEO))
                .thenReturn(videoModel());
        UUID assetId = UUID.randomUUID();
        UUID bogusVersionId = UUID.randomUUID();
        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setKind(AssetKind.IMAGE);
        when(assetService.getForConversation(USER_ID, CONVERSATION_ID, assetId)).thenReturn(asset);
        doThrow(new BusinessException(AssetErrorCode.FILE_VERSION_NOT_FOUND))
                .when(fileService)
                .requireVersionInConversation(USER_ID, CONVERSATION_ID, bogusVersionId, assetId);

        SubmitGenerationCommand cmd = new SubmitGenerationCommand(
                CONVERSATION_ID,
                null,
                null,
                GenerationType.VIDEO,
                MODEL_ID,
                "animate scene",
                "{}",
                List.of(new SubmitInput(assetId, bogusVersionId, GenerationInputRole.FIRST_FRAME)),
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.submit(USER_ID, cmd))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AgentErrorCode.GENERATION_INPUT_INVALID));

        verify(inputMapper, never()).insert(any(GenerationInput.class));
        verify(taskMapper, never()).insert(any(GenerationTask.class));
    }

    private ModelDefinition videoModel() {
        ModelDefinition model = new ModelDefinition();
        model.setId(MODEL_ID);
        model.setModelId("mock-video");
        model.setCapabilityType(ModelCapabilityType.VIDEO);
        model.setProtocol(MediaProtocol.ARK_VIDEO_TASKS);
        model.setExecutionMode(ExecutionMode.REMOTE_TASK);
        model.setPriceUnits(20L);
        model.setEnabled(true);
        return model;
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
