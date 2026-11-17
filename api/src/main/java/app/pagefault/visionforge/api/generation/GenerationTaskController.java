package app.pagefault.visionforge.api.generation;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.generation.GenerationSubmission;
import app.pagefault.visionforge.agent.generation.GenerationTask;
import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.agent.generation.SubmitGenerationCommand;
import app.pagefault.visionforge.agent.generation.SubmitInput;
import app.pagefault.visionforge.api.generation.dto.CreateGenerationTaskRequest;
import app.pagefault.visionforge.api.generation.dto.GenerationInputRequest;
import app.pagefault.visionforge.api.generation.dto.GenerationTaskResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.common.api.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/generation-tasks")
@RequiredArgsConstructor
public class GenerationTaskController {

    private final GenerationTaskService taskService;
    private final GenerationTaskStarter taskStarter;

    @PostMapping
    public ResponseEntity<ApiResponse<GenerationTaskResponse>> create(
            @Valid @RequestBody CreateGenerationTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        GenerationSubmission submission = taskService.submit(
                AuthenticatedUser.id(jwt),
                new SubmitGenerationCommand(
                        request.conversationId(),
                        null,
                        null,
                        request.type(),
                        request.modelDefinitionId(),
                        request.prompt(),
                        request.parameters() == null ? "{}" : request.parameters().toString(),
                        inputs(request.inputs()),
                        idempotencyKey,
                        request.sourceTaskId()));
        if (submission.created()) {
            taskStarter.start(submission.task().getId(), submission.workflowId());
        }
        GenerationTask task = submission.task();
        GenerationTaskResponse response = GenerationTaskResponse.from(
                task, taskService.inputs(task.getId()), taskService.outputs(task.getId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/generation-tasks/" + task.getId()))
                .body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<List<GenerationTaskResponse>> list(
            @RequestParam(required = false) UUID conversationId,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        List<GenerationTaskResponse> tasks = taskService
                .list(AuthenticatedUser.id(jwt), conversationId, limit).stream()
                .map(task -> GenerationTaskResponse.from(
                        task, taskService.inputs(task.getId()), taskService.outputs(task.getId())))
                .toList();
        return ApiResponse.success(tasks);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<GenerationTaskResponse> get(
            @PathVariable UUID taskId, @AuthenticationPrincipal Jwt jwt) {
        GenerationTask task = taskService.getForUser(AuthenticatedUser.id(jwt), taskId);
        return ApiResponse.success(GenerationTaskResponse.from(
                task, taskService.inputs(taskId), taskService.outputs(taskId)));
    }

    @PostMapping("/{taskId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<GenerationTaskResponse> cancel(
            @PathVariable UUID taskId, @AuthenticationPrincipal Jwt jwt) {
        GenerationTask task = taskService.cancel(AuthenticatedUser.id(jwt), taskId);
        return ApiResponse.success(GenerationTaskResponse.from(
                task, taskService.inputs(taskId), taskService.outputs(taskId)));
    }

    /**
     * Repairs delivery after an archive failure without calling the gateway again.
     */
    @PostMapping("/{taskId}/retry-archive")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<GenerationTaskResponse> retryArchive(
            @PathVariable UUID taskId, @AuthenticationPrincipal Jwt jwt) {
        GenerationTask task = taskService.retryArchive(AuthenticatedUser.id(jwt), taskId);
        return ApiResponse.success(GenerationTaskResponse.from(
                task, taskService.inputs(taskId), taskService.outputs(taskId)));
    }

    private List<SubmitInput> inputs(List<GenerationInputRequest> inputs) {
        if (inputs == null) {
            return List.of();
        }
        return inputs.stream()
                .map(input -> new SubmitInput(
                        input.assetId(), input.fileVersionId(), input.role()))
                .toList();
    }
}
