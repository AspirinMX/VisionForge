package app.pagefault.visionforge.api.config.dto;

import java.time.Instant;

import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;

public record ConfigEntryResponse(
        String dataId,
        String description,
        String content,
        String defaultContent,
        String source,
        long version,
        Instant updatedAt) {

    public static ConfigEntryResponse from(DynamicRuntimeConfig.ConfigEntry entry) {
        return new ConfigEntryResponse(
                entry.dataId(),
                entry.description(),
                entry.content(),
                entry.defaultContent(),
                entry.source(),
                entry.version(),
                entry.updatedAt());
    }
}
