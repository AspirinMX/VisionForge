package app.pagefault.visionforge.api.config.dto;

import java.util.List;

import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;

public record ConfigCenterOverviewResponse(
        boolean enabled,
        boolean connected,
        String serverAddr,
        String namespace,
        String group,
        String source,
        List<ConfigEntryResponse> entries) {

    public static ConfigCenterOverviewResponse from(
            DynamicRuntimeConfig.Status status,
            List<DynamicRuntimeConfig.ConfigEntry> entries) {
        return new ConfigCenterOverviewResponse(
                status.enabled(),
                status.connected(),
                status.serverAddr(),
                status.namespace(),
                status.group(),
                status.source(),
                entries.stream().map(ConfigEntryResponse::from).toList());
    }
}
