package app.pagefault.visionforge.api.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.config.dto.ConfigCenterOverviewResponse;
import app.pagefault.visionforge.api.config.dto.ConfigEntryResponse;
import app.pagefault.visionforge.api.config.dto.UpdateConfigRequest;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/configs")
@RequiredArgsConstructor
public class AdminConfigController {

    private final DynamicRuntimeConfig dynamicRuntimeConfig;

    @GetMapping
    public ApiResponse<ConfigCenterOverviewResponse> list() {
        return ApiResponse.success(ConfigCenterOverviewResponse.from(
                dynamicRuntimeConfig.statusSummary(),
                dynamicRuntimeConfig.listEntries()));
    }

    @GetMapping("/{dataId}")
    public ApiResponse<ConfigEntryResponse> get(@PathVariable String dataId) {
        return ApiResponse.success(ConfigEntryResponse.from(dynamicRuntimeConfig.getEntry(dataId)));
    }

    @PutMapping("/{dataId}")
    public ApiResponse<ConfigEntryResponse> publish(
            @PathVariable String dataId,
            @Valid @RequestBody UpdateConfigRequest request) {
        return ApiResponse.success(ConfigEntryResponse.from(
                dynamicRuntimeConfig.publish(dataId, request.content())));
    }

    @PostMapping("/{dataId}/reset")
    public ApiResponse<ConfigEntryResponse> reset(@PathVariable String dataId) {
        return ApiResponse.success(ConfigEntryResponse.from(dynamicRuntimeConfig.reset(dataId)));
    }
}
