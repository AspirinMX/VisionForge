package app.pagefault.visionforge.api.config.dto;

import java.time.Instant;

import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;

public record ConfigEntryResponse(
        String dataId,
        String group,
        String namespace,
        String description,
        String content,
        String defaultContent,
        String md5,
        String source,
        boolean nacosEnabled,
        boolean nacosConnected,
        Instant updatedAt) {

    public static ConfigEntryResponse from(DynamicRuntimeConfig.ConfigEntry entry) {
        return new ConfigEntryResponse(
                entry.dataId(),
                entry.group(),
                entry.namespace(),
                entry.description(),
                entry.content(),
                entry.defaultContent(),
                entry.md5(),
                entry.source(),
                entry.nacosEnabled(),
                entry.nacosConnected(),
                entry.updatedAt());
    }
}
