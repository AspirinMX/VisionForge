package app.pagefault.visionforge.api.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
import app.pagefault.visionforge.common.alert.AlertEvent;
import app.pagefault.visionforge.common.alert.AlertPublisher;
import app.pagefault.visionforge.common.alert.AlertSeverity;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/configs")
@RequiredArgsConstructor
public class AdminConfigController {

    private final DynamicRuntimeConfig dynamicRuntimeConfig;
    private final ObjectProvider<AlertPublisher> alertPublisherProvider;

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
            @Valid @RequestBody UpdateConfigRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        DynamicRuntimeConfig.ConfigEntry published = dynamicRuntimeConfig.publish(
                AuthenticatedUser.id(jwt), dataId, request.content());
        AlertPublisher publisher = alertPublisherProvider.getIfAvailable();
        if (publisher != null) {
            publisher.publish(AlertEvent.of(
                    AlertEvent.EVENT_ADMIN_SECURITY_CHANGE,
                    AlertSeverity.INFO,
                    "visionforge-api",
                    "管理员更新动态配置: " + dataId,
                    "配置来源=" + published.source() + ", MD5=" + published.md5(),
                    dataId));
        }
        return ApiResponse.success(ConfigEntryResponse.from(published));
    }

    @PostMapping("/{dataId}/reset")
    public ApiResponse<ConfigEntryResponse> reset(
            @PathVariable String dataId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(ConfigEntryResponse.from(
                dynamicRuntimeConfig.reset(AuthenticatedUser.id(jwt), dataId)));
    }
}
