package app.pagefault.visionforge.api.model;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.model.ModelDefinition;
import app.pagefault.visionforge.agent.model.ModelDefinitionInput;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.api.model.dto.CreateModelRequest;
import app.pagefault.visionforge.api.model.dto.ModelResponse;
import app.pagefault.visionforge.api.model.dto.UpdateModelRequest;
import app.pagefault.visionforge.common.api.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/models")
@RequiredArgsConstructor
public class AdminModelController {

    private final ModelDefinitionService modelDefinitionService;

    @GetMapping
    public ApiResponse<List<ModelResponse>> list() {
        List<ModelResponse> models = modelDefinitionService.list().stream()
                .map(ModelResponse::from)
                .toList();
        return ApiResponse.success(models);
    }

    @GetMapping("/{id}")
    public ApiResponse<ModelResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(ModelResponse.from(modelDefinitionService.get(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ModelResponse> create(@Valid @RequestBody CreateModelRequest request) {
        ModelDefinition model = modelDefinitionService.create(new ModelDefinitionInput(
                request.displayName(),
                request.baseUrl(),
                request.apiKey(),
                request.modelId(),
                request.reasoningEffort(),
                request.reasoningReplay(),
                request.maxInputTokens(),
                request.maxOutputTokens(),
                request.enabled(),
                request.defaultModel(),
                request.capabilityType(),
                request.protocol(),
                request.executionMode(),
                request.priceUnits(),
                request.supportsImageInput()));
        return ApiResponse.success(ModelResponse.from(model));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ModelResponse> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateModelRequest request) {
        ModelDefinition model = modelDefinitionService.update(id, new ModelDefinitionInput(
                request.displayName(),
                request.baseUrl(),
                request.apiKey(),
                request.modelId(),
                request.reasoningEffort(),
                request.reasoningReplay(),
                request.maxInputTokens(),
                request.maxOutputTokens(),
                request.enabled(),
                request.defaultModel(),
                request.capabilityType(),
                request.protocol(),
                request.executionMode(),
                request.priceUnits(),
                request.supportsImageInput()));
        return ApiResponse.success(ModelResponse.from(model));
    }

    @PostMapping("/{id}/default")
    public ApiResponse<ModelResponse> setDefault(@PathVariable UUID id) {
        return ApiResponse.success(ModelResponse.from(modelDefinitionService.setDefault(id)));
    }
}
