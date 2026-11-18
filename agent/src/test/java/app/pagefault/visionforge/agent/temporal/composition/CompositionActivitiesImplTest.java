package app.pagefault.visionforge.agent.temporal.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import app.pagefault.visionforge.agent.composition.CompositionInput;
import app.pagefault.visionforge.agent.composition.CompositionProperties;
import app.pagefault.visionforge.agent.composition.CompositionSpec;
import app.pagefault.visionforge.agent.composition.CompositionTask;
import app.pagefault.visionforge.agent.composition.CompositionTaskService;
import app.pagefault.visionforge.agent.composition.FfmpegCommandBuilder;
import app.pagefault.visionforge.agent.composition.FfmpegRunner;
import app.pagefault.visionforge.asset.library.ConversationFile;
import app.pagefault.visionforge.asset.library.ConversationFileKind;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationFileVersion;
import app.pagefault.visionforge.asset.library.PublishedMediaFile;
import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetKind;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.asset.media.AssetStatus;
import app.pagefault.visionforge.asset.storage.ObjectStorage;
import app.pagefault.visionforge.asset.storage.PresignedUrl;
import app.pagefault.visionforge.asset.storage.StorageBackend;
import app.pagefault.visionforge.asset.storage.StorageMultipartUpload;
import app.pagefault.visionforge.asset.storage.StoredObject;
import app.pagefault.visionforge.asset.storage.UploadedPart;

import tools.jackson.databind.ObjectMapper;

class CompositionActivitiesImplTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONVERSATION_ID = UUID.randomUUID();
    private static final UUID ASSET_ID = UUID.randomUUID();
    private static final byte[] OUTPUT = "fake-mp4".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @ValueSource(strings = {"1.000", "0.400"})
    void validatesSourceDurationBeforePublishingFinalAsset(String sourceDuration) {
        CompositionTaskService taskService = mock(CompositionTaskService.class);
        AssetService assetService = mock(AssetService.class);
        ConversationFileService fileService = mock(ConversationFileService.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ObjectStorage> storageProvider = mock(ObjectProvider.class);

        CompositionTask task = task();
        when(taskService.get(TASK_ID)).thenReturn(task);
        when(taskService.cancelled(TASK_ID)).thenReturn(false);
        when(taskService.inputs(TASK_ID)).thenReturn(List.of(input()));

        Asset inputAsset = asset();
        when(assetService.get(USER_ID, ASSET_ID)).thenReturn(inputAsset);
        when(storage.presignInternalGet(any(), any()))
                .thenReturn(new PresignedUrl(
                        java.net.URI.create("https://internal/clip.mp4"),
                        java.time.Instant.now().plusSeconds(600), java.util.Map.of()));
        StorageMultipartUpload upload =
                new StorageMultipartUpload("bucket", "composition/final.mp4", "upload-1");
        when(storage.initiateMultipart(any(), any())).thenReturn(upload);
        when(storage.uploadPart(eq(upload), anyInt(), any(), anyLong()))
                .thenReturn(new UploadedPart(1, "etag-1"));
        when(storage.completeMultipart(eq(upload), any()))
                .thenReturn(new StoredObject("composition/final.mp4", OUTPUT.length, "etag", "hash"));
        when(storageProvider.getIfAvailable()).thenReturn(storage);

        Asset outputAsset = asset();
        when(assetService.registerStoredAsset(
                eq(USER_ID), eq(CONVERSATION_ID), eq(AssetKind.VIDEO),
                eq("video/mp4"), any(), eq((long) OUTPUT.length), any()))
                .thenReturn(outputAsset);
        ConversationFile file = mock(ConversationFile.class);
        ConversationFileVersion version = mock(ConversationFileVersion.class);
        UUID fileId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(file.getId()).thenReturn(fileId);
        when(version.getId()).thenReturn(versionId);
        when(version.getVersion()).thenReturn(1);
        when(fileService.createMediaFile(
                eq(USER_ID), eq(CONVERSATION_ID), eq(ConversationFileKind.VIDEO),
                any(), eq(ASSET_ID), any(), anyLong(), any()))
                .thenReturn(new PublishedMediaFile(file, version));

        FfmpegRunner runner = command -> new FakeProcess(command.getFirst().equals("ffprobe")
                ? sourceDuration.getBytes(StandardCharsets.UTF_8) : OUTPUT);
        CompositionActivitiesImpl activities = new CompositionActivitiesImpl(
                taskService,
                assetService,
                fileService,
                storageProvider,
                new FfmpegCommandBuilder(),
                runner,
                new CompositionProperties(
                        "ffmpeg", "ffprobe", 5L * 1024 * 1024, Duration.ofSeconds(30), Duration.ofHours(1)),
                new ObjectMapper());

        if (sourceDuration.equals("0.400")) {
            assertThatThrownBy(() -> activities.compose(TASK_ID))
                    .hasMessageContaining("镜头 shot-1 的片段时长为 0.400 秒，无法覆盖剪辑区间 0–1 秒");
            verify(storage, never()).initiateMultipart(any(), any());
            verify(taskService).markFailed(eq(TASK_ID), any());
            return;
        }

        activities.compose(TASK_ID);

        verify(storage).initiateMultipart(any(), eq("video/mp4"));
        verify(storage).uploadPart(eq(upload), eq(1), any(), eq((long) OUTPUT.length));
        verify(storage).completeMultipart(eq(upload), any());
        verify(taskService).markSucceeded(TASK_ID, ASSET_ID, fileId, versionId);
    }

    private CompositionTask task() {
        CompositionTask task = new CompositionTask();
        task.setId(TASK_ID);
        task.setUserId(USER_ID);
        task.setConversationId(CONVERSATION_ID);
        task.setStoryboardFileId(UUID.randomUUID());
        task.setStoryboardVersion(1);
        task.setOutputSpec("{}");
        task.setStatus(app.pagefault.visionforge.agent.composition.CompositionStatus.PENDING);
        return task;
    }

    private CompositionInput input() {
        CompositionInput input = new CompositionInput();
        input.setTaskId(TASK_ID);
        input.setShotId("shot-1");
        input.setGenerationTaskId(UUID.randomUUID());
        input.setAssetId(ASSET_ID);
        input.setStartSeconds(BigDecimal.ZERO);
        input.setEndSeconds(BigDecimal.ONE);
        input.setOrdinal(0);
        return input;
    }

    private Asset asset() {
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setUserId(USER_ID);
        asset.setConversationId(CONVERSATION_ID);
        asset.setKind(AssetKind.VIDEO);
        asset.setStorageBackend(StorageBackend.S3);
        asset.setBucket("bucket");
        asset.setObjectKey("clips/clip.mp4");
        asset.setMimeType("video/mp4");
        asset.setSizeBytes(10);
        asset.setStatus(AssetStatus.READY);
        return asset;
    }

    static final class FakeProcess extends Process {

        private final ByteArrayInputStream stdout;
        private final ByteArrayInputStream stderr = new ByteArrayInputStream(new byte[0]);
        private boolean destroyed;

        FakeProcess(byte[] output) {
            this.stdout = new ByteArrayInputStream(output);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
