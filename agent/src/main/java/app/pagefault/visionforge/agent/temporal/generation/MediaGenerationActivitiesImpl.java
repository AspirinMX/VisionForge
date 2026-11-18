package app.pagefault.visionforge.agent.temporal.generation;

import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.generation.GenerationAttempt;
import app.pagefault.visionforge.agent.generation.GenerationStatus;
import app.pagefault.visionforge.agent.generation.GenerationTask;
import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.agent.provider.media.MediaProvider;
import app.pagefault.visionforge.agent.provider.media.MediaProviderOutcome;
import app.pagefault.visionforge.agent.provider.media.MediaProviderRegistry;
import app.pagefault.visionforge.agent.provider.media.MediaProviderRequest;
import app.pagefault.visionforge.agent.provider.media.RemoteMediaProvider;
import app.pagefault.visionforge.common.error.BusinessException;

import io.temporal.failure.ApplicationFailure;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MediaGenerationActivitiesImpl implements MediaGenerationActivities {

    private final GenerationTaskService taskService;
    private final MediaProviderRegistry providerRegistry;

    @Override
    public GenerationSubmitResult submit(UUID taskId) {
        GenerationTask task = taskService.get(taskId);
        if (task.getStatus() == GenerationStatus.SUCCEEDED || task.getStatus().terminal()) {
            return new GenerationSubmitResult(false, true);
        }
        if (task.getStatus() == GenerationStatus.PROCESSING) {
            return new GenerationSubmitResult(false, false);
        }
        if (task.getStatus() == GenerationStatus.RUNNING) {
            return new GenerationSubmitResult(true, false);
        }

        UUID attemptId = null;
        try {
            GenerationAttempt attempt = taskService.beginAttempt(taskId);
            attemptId = attempt.getId();
            MediaProviderRequest request =
                    taskService.providerRequest(task, taskService.inputs(taskId));
            MediaProvider provider = providerRegistry.require(task.getProtocol());
            MediaProviderOutcome outcome = provider.start(request);
            if (outcome instanceof MediaProviderOutcome.Completed completed) {
                taskService.recordCompleted(
                        taskId, attemptId, completed.receipt(), completed.artifacts());
                return new GenerationSubmitResult(false, false);
            }
            if (outcome instanceof MediaProviderOutcome.Pending pending) {
                taskService.recordPending(
                        taskId, attemptId, pending.receipt(), pending.remoteTaskId());
                return new GenerationSubmitResult(true, false);
            }
            if (outcome instanceof MediaProviderOutcome.Cancelled cancelled) {
                taskService.markCancelled(taskId, attemptId, cancelled.message());
                return new GenerationSubmitResult(false, true);
            }
            MediaProviderOutcome.Failed failed = (MediaProviderOutcome.Failed) outcome;
            taskService.markFailed(taskId, attemptId, failed.message());
            return new GenerationSubmitResult(false, true);
        } catch (BusinessException exception) {
            failUnlessArchiveFailed(taskId, attemptId, exception.getMessage());
            throw ApplicationFailure.newNonRetryableFailure(
                    exception.getMessage(), exception.getErrorCode().code());
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? AgentErrorCode.GENERATION_PROVIDER_ERROR.message()
                    : exception.getMessage();
            failUnlessArchiveFailed(taskId, attemptId, message);
            throw ApplicationFailure.newNonRetryableFailure(
                    message, AgentErrorCode.GENERATION_PROVIDER_ERROR.code());
        }
    }

    /**
     * An archive failure keeps the task and its reservation: the asset may already be stored and
     * delivery can be retried without a new paid generation.
     */
    private void failUnlessArchiveFailed(UUID taskId, UUID attemptId, String message) {
        GenerationTask current = taskService.get(taskId);
        if (current.getStatus() == GenerationStatus.ARCHIVE_FAILED) {
            return;
        }
        taskService.markFailed(taskId, attemptId, message);
    }

    @Override
    public GenerationSubmitResult poll(UUID taskId) {
        GenerationTask task = taskService.get(taskId);
        if (task.getStatus() == GenerationStatus.PROCESSING) {
            return new GenerationSubmitResult(false, false);
        }
        if (task.getStatus().terminal()) {
            return new GenerationSubmitResult(false, true);
        }
        GenerationAttempt attempt = taskService.findAttempt(taskId, 1);
        if (attempt == null || attempt.getRemoteTaskId() == null) {
            taskService.markUnknown(taskId, null, "远端任务标识缺失，结果待核对");
            return new GenerationSubmitResult(false, true);
        }
        MediaProvider provider = providerRegistry.require(task.getProtocol());
        if (!(provider instanceof RemoteMediaProvider remoteProvider)) {
            taskService.markUnknown(taskId, attempt.getId(), "协议不支持查询远端任务，结果待核对");
            return new GenerationSubmitResult(false, true);
        }
        try {
            MediaProviderRequest request =
                    taskService.providerRequest(task, java.util.List.of());
            MediaProviderOutcome outcome =
                    remoteProvider.query(request, attempt.getRemoteTaskId());
            if (outcome instanceof MediaProviderOutcome.Completed completed) {
                taskService.recordCompleted(
                        taskId, attempt.getId(), completed.receipt(), completed.artifacts());
                return new GenerationSubmitResult(false, false);
            }
            if (outcome instanceof MediaProviderOutcome.Pending pending) {
                taskService.recordPending(
                        taskId, attempt.getId(), pending.receipt(), pending.remoteTaskId());
                return new GenerationSubmitResult(true, false);
            }
            if (outcome instanceof MediaProviderOutcome.Cancelled cancelled) {
                taskService.markCancelled(taskId, attempt.getId(), cancelled.message());
                return new GenerationSubmitResult(false, true);
            }
            MediaProviderOutcome.Failed failed = (MediaProviderOutcome.Failed) outcome;
            taskService.markFailed(taskId, attempt.getId(), failed.message());
            return new GenerationSubmitResult(false, true);
        } catch (BusinessException exception) {
            taskService.markUnknown(taskId, attempt.getId(), exception.getMessage());
            return new GenerationSubmitResult(false, true);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? AgentErrorCode.GENERATION_PROVIDER_ERROR.message()
                    : exception.getMessage();
            taskService.markUnknown(taskId, attempt.getId(), message);
            return new GenerationSubmitResult(false, true);
        }
    }

    @Override
    public void settle(UUID taskId) {
        taskService.settle(taskId);
    }

    @Override
    public void fail(UUID taskId, String errorMessage) {
        GenerationTask task = taskService.get(taskId);
        if (task.getStatus().terminal()) {
            return;
        }
        if (task.getStatus() == GenerationStatus.ARCHIVE_FAILED) {
            return;
        }
        if (task.getStatus() == GenerationStatus.PROCESSING) {
            // Outputs were archived; only settlement is missing.
            taskService.settle(taskId);
            return;
        }
        GenerationAttempt attempt = taskService.findAttempt(taskId, 1);
        UUID attemptId = attempt == null ? null : attempt.getId();
        if (task.getStatus() == GenerationStatus.RUNNING
                || task.getStatus() == GenerationStatus.SUBMITTING) {
            taskService.markUnknown(taskId, attemptId, errorMessage);
        } else {
            taskService.markFailed(taskId, attemptId, errorMessage);
        }
    }
}
