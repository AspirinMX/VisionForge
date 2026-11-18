package app.pagefault.visionforge.api.composition;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.composition.CompositionInput;
import app.pagefault.visionforge.agent.composition.CompositionStatus;
import app.pagefault.visionforge.agent.composition.CompositionSubmission;
import app.pagefault.visionforge.agent.composition.CompositionTask;
import app.pagefault.visionforge.agent.composition.CompositionTaskService;
import app.pagefault.visionforge.agent.composition.SubmitCompositionCommand;
import app.pagefault.visionforge.api.composition.dto.CompositionTaskResponse;
import app.pagefault.visionforge.api.composition.dto.CreateCompositionTaskRequest;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.common.api.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/composition-tasks")
@RequiredArgsConstructor
public class CompositionTaskController {

    private final CompositionTaskService taskService;
    private final CompositionTaskStarter taskStarter;

    @PostMapping
    public ResponseEntity<ApiResponse<CompositionTaskResponse>> create(
            @Valid @RequestBody CreateCompositionTaskRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        CompositionSubmission submission = taskService.submit(
                AuthenticatedUser.id(jwt),
                new SubmitCompositionCommand(
                        request.conversationId(),
                        request.storyboardFileId(),
                        request.storyboardVersion(),
                        request.outputSpec() == null ? null : request.outputSpec().toString()));
        if (submission.created()) {
            taskStarter.start(submission.task().getId(), submission.workflowId());
        }
        CompositionTask task = submission.task();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/composition-tasks/" + task.getId()))
                .body(ApiResponse.success(CompositionTaskResponse.from(
                        task, taskService.inputs(task.getId()))));
    }

    @GetMapping
    public ApiResponse<List<CompositionTaskResponse>> list(
            @RequestParam(required = false) UUID conversationId,
            @RequestParam(required = false) CompositionStatus status,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        List<CompositionTask> tasks =
                taskService.list(AuthenticatedUser.id(jwt), conversationId, status, limit);
        Map<UUID, List<CompositionInput>> inputs = taskService.inputsByTaskIds(
                tasks.stream().map(CompositionTask::getId).toList());
        return ApiResponse.success(tasks.stream()
                .map(task -> CompositionTaskResponse.from(
                        task, inputs.getOrDefault(task.getId(), List.of())))
                .toList());
    }

    @GetMapping("/{taskId}")
    public ApiResponse<CompositionTaskResponse> get(
            @PathVariable UUID taskId, @AuthenticationPrincipal Jwt jwt) {
        CompositionTask task = taskService.getForUser(AuthenticatedUser.id(jwt), taskId);
        return ApiResponse.success(CompositionTaskResponse.from(
                task, taskService.inputs(taskId)));
    }

    @PostMapping("/{taskId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CompositionTaskResponse> cancel(
            @PathVariable UUID taskId, @AuthenticationPrincipal Jwt jwt) {
        CompositionTask task = taskService.cancel(AuthenticatedUser.id(jwt), taskId);
        return ApiResponse.success(CompositionTaskResponse.from(
                task, taskService.inputs(taskId)));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<ApiResponse<CompositionTaskResponse>> retry(
            @PathVariable UUID taskId, @AuthenticationPrincipal Jwt jwt) {
        CompositionSubmission submission = taskService.retry(
                AuthenticatedUser.id(jwt), taskId);
        taskStarter.start(submission.task().getId(), submission.workflowId());
        CompositionTask task = submission.task();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/composition-tasks/" + task.getId()))
                .body(ApiResponse.success(CompositionTaskResponse.from(
                        task, taskService.inputs(task.getId()))));
    }
}
