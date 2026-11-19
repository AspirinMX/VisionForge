package app.pagefault.visionforge.api.alert.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.agent.alert.AlertChannel;
import app.pagefault.visionforge.agent.alert.AlertChannelType;
import app.pagefault.visionforge.common.alert.AlertSeverity;

public record AlertChannelResponse(
        UUID id,
        String name,
        AlertChannelType channelType,
        String target,
        boolean secretConfigured,
        String subscribedEvents,
        AlertSeverity minSeverity,
        boolean enabled,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static AlertChannelResponse from(AlertChannel channel) {
        return new AlertChannelResponse(
                channel.getId(),
                channel.getName(),
                channel.getChannelType(),
                channel.getTarget(),
                channel.getSecretCiphertext() != null && !channel.getSecretCiphertext().isBlank(),
                channel.getSubscribedEvents(),
                channel.getMinSeverity(),
                channel.isEnabled(),
                channel.getCreatedBy(),
                channel.getCreatedAt(),
                channel.getUpdatedAt());
    }
}
