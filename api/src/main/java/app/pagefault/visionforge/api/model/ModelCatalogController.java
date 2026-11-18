package app.pagefault.visionforge.api.model;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.model.ModelCapabilityType;
import app.pagefault.visionforge.agent.model.ModelDefinitionService;
import app.pagefault.visionforge.api.model.dto.PublicModelResponse;
import app.pagefault.visionforge.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * Model catalog for regular users. It exposes only enabled models and never leaks provider
 * endpoints or API keys; generation still defaults to the server-side route when no id is sent.
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelCatalogController {

    private final ModelDefinitionService modelDefinitionService;

    @GetMapping
    public ApiResponse<List<PublicModelResponse>> list(
            @RequestParam(required = false) ModelCapabilityType capabilityType) {
        return ApiResponse.success(modelDefinitionService.listEnabled(capabilityType).stream()
                .map(PublicModelResponse::from)
                .toList());
    }
}
