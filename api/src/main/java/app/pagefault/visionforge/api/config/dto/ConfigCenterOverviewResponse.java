package app.pagefault.visionforge.api.config.dto;

import java.util.List;

import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;

public record ConfigCenterOverviewResponse(
        boolean enabled,
        boolean connected,
        String source,
        List<ConfigEntryResponse> entries) {

    public static ConfigCenterOverviewResponse from(
            DynamicRuntimeConfig.Status status,
            List<DynamicRuntimeConfig.ConfigEntry> entries) {
        return new ConfigCenterOverviewResponse(
                status.enabled(),
                status.connected(),
                status.source(),
                entries.stream().map(ConfigEntryResponse::from).toList());
    }
}
