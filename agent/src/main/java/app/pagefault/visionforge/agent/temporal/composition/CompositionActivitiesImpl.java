package app.pagefault.visionforge.agent.temporal.composition;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;

import app.pagefault.visionforge.agent.composition.CompositionInput;
import app.pagefault.visionforge.agent.composition.CompositionProperties;
import app.pagefault.visionforge.agent.composition.CompositionSpec;
import app.pagefault.visionforge.agent.composition.CompositionTask;
import app.pagefault.visionforge.agent.composition.CompositionTaskService;
import app.pagefault.visionforge.agent.composition.FfmpegCommandBuilder;
import app.pagefault.visionforge.agent.composition.FfmpegCommandBuilder.CompositionClip;
import app.pagefault.visionforge.agent.composition.FfmpegRunner;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.PublishedMediaFile;
import app.pagefault.visionforge.asset.media.Asset;
import app.pagefault.visionforge.asset.media.AssetKind;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.asset.media.AssetStatus;
import app.pagefault.visionforge.asset.storage.ObjectKeys;
import app.pagefault.visionforge.asset.storage.ObjectStorage;
import app.pagefault.visionforge.asset.storage.StorageMultipartUpload;
import app.pagefault.visionforge.asset.storage.StoredObject;
import app.pagefault.visionforge.asset.storage.UploadedPart;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.SystemException;

import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class CompositionActivitiesImpl implements CompositionActivities {

    private static final int STDERR_LIMIT = 16 * 1024;

    private final CompositionTaskService taskService;
    private final AssetService assetService;
    private final ConversationFileService conversationFileService;
    private final ObjectProvider<ObjectStorage> storageProvider;
    private final FfmpegCommandBuilder commandBuilder;
    private final FfmpegRunner ffmpegRunner;
    private final CompositionProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void compose(UUID taskId) {
        CompositionTask task = taskService.get(taskId);
        if (task.getStatus().terminal()) {
            return;
        }
        if (taskService.cancelled(taskId)) {
            return;
        }
        ObjectStorage storage = storage();
        StorageMultipartUpload upload = null;
        Process process = null;
        long totalBytes = 0;
        try {
            taskService.markComposing(taskId);
            List<CompositionInput> inputs = taskService.inputs(taskId);
            List<CompositionClip> clips = new ArrayList<>(inputs.size());
            for (CompositionInput input : inputs) {
                Asset asset = assetService.get(task.getUserId(), input.getAssetId());
                if (!task.getConversationId().equals(asset.getConversationId())
                        || asset.getStatus() != AssetStatus.READY) {
                    throw new BusinessException(
                            app.pagefault.visionforge.agent.error.AgentErrorCode.GENERATION_INPUT_INVALID,
                            "成片输入资产不可用");
                }
                String url = storage.presignInternalGet(
                        asset.getObjectKey(), properties.presignTtlOrDefault()).url().toString();
                clips.add(new CompositionClip(
                        url, input.getStartSeconds(), input.getEndSeconds()));
            }
            JsonNode specNode = objectMapper.readTree(task.getOutputSpec());
            CompositionSpec spec =
                    commandBuilder.parseSpec(specNode, commandBuilder.defaults());
            List<String> command = commandBuilder.build(
                    properties.ffmpegPathOrDefault(), clips, spec);

            process = ffmpegRunner.start(command);
            final Process activeProcess = process;
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread stderrThread = Thread.startVirtualThread(
                    () -> drain(activeProcess.getErrorStream(), stderr));

            String objectKey = ObjectKeys.composition(
                    task.getUserId(), task.getConversationId(), taskId, "mp4");
            upload = storage.initiateMultipart(objectKey, "video/mp4");
            taskService.markUploading(taskId, upload.uploadId());

            List<UploadedPart> parts = new ArrayList<>();
            byte[] buffer = new byte[Math.toIntExact(properties.partSizeBytesOrDefault())];
            int partNumber = 1;
            int read;
            while ((read = readChunk(process.getInputStream(), buffer)) > 0) {
                if (taskService.cancelled(taskId)) {
                    process.destroyForcibly();
                    storage.abortMultipart(upload);
                    return;
                }
                parts.add(storage.uploadPart(
                        upload,
                        partNumber++,
                        new ByteArrayInputStream(buffer, 0, read),
                        read));
                totalBytes += read;
                heartbeat(totalBytes);
            }

            Duration timeout = properties.timeoutOrDefault();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                stderrThread.join(1_000);
                throw new BusinessException(
                        app.pagefault.visionforge.agent.error.AgentErrorCode.GENERATION_ARCHIVE_FAILED,
                        "FFmpeg 合成超时");
            }
            stderrThread.join(1_000);
            int exit = process.exitValue();
            if (exit != 0 || parts.isEmpty()) {
                String detail = stderr.toString(StandardCharsets.UTF_8);
                throw new BusinessException(
                        app.pagefault.visionforge.agent.error.AgentErrorCode.GENERATION_ARCHIVE_FAILED,
                        "FFmpeg 合成失败（exit=" + exit + "）：" + tail(detail));
            }

            StoredObject stored = storage.completeMultipart(upload, parts);
            upload = null;
            taskService.markPublishing(taskId);
            Asset asset = assetService.registerStoredAsset(
                    task.getUserId(),
                    task.getConversationId(),
                    AssetKind.VIDEO,
                    "video/mp4",
                    objectKey,
                    stored.sizeBytes() > 0 ? stored.sizeBytes() : totalBytes,
                    stored.checksum());
            PublishedMediaFile published = conversationFileService.createMediaFile(
                    task.getUserId(),
                    task.getConversationId(),
                    app.pagefault.visionforge.asset.library.ConversationFileKind.VIDEO,
                    "成片 " + taskId.toString().substring(0, 8),
                    asset.getId(),
                    asset.getMimeType(),
                    asset.getSizeBytes(),
                    asset.getChecksum());
            taskService.markSucceeded(
                    taskId,
                    asset.getId(),
                    published.file().getId(),
                    published.version().getId());
        } catch (RuntimeException | IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (upload != null) {
                try {
                    storage.abortMultipart(upload);
                } catch (RuntimeException ignored) {
                    // The original failure is more useful than a cleanup failure.
                }
            }
            String message = message(exception);
            taskService.markFailed(taskId, message);
            throw ApplicationFailure.newNonRetryableFailure(
                    message,
                    app.pagefault.visionforge.agent.error.AgentErrorCode.GENERATION_ARCHIVE_FAILED.code());
        }
    }

    @Override
    public void fail(UUID taskId, String errorMessage) {
        taskService.markFailed(taskId, errorMessage);
    }

    private void heartbeat(long bytes) {
        try {
            Activity.getExecutionContext().heartbeat(bytes);
        } catch (RuntimeException ignored) {
            // Outside a Temporal activity context (unit tests) there is nothing to heartbeat.
        }
    }

    private ObjectStorage storage() {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new SystemException("对象存储未配置", null);
        }
        return storage;
    }

    private int readChunk(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private void drain(InputStream input, ByteArrayOutputStream output) {
        try (InputStream stream = input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) > 0 && output.size() < STDERR_LIMIT) {
                output.write(buffer, 0, Math.min(read, STDERR_LIMIT - output.size()));
            }
        } catch (IOException ignored) {
            // The process failure path already reports the useful error.
        }
    }

    private String tail(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 1_000 ? trimmed : trimmed.substring(trimmed.length() - 1_000);
    }

    private String message(Throwable exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
