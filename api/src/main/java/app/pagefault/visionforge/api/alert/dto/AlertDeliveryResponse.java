package app.pagefault.visionforge.api.alert.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.agent.alert.AlertChannelType;
import app.pagefault.visionforge.agent.alert.AlertDelivery;
import app.pagefault.visionforge.agent.alert.AlertDeliveryStatus;
import app.pagefault.visionforge.common.alert.AlertSeverity;

public record AlertDeliveryResponse(
        UUID id,
        UUID channelId,
        String channelName,
        AlertChannelType channelType,
        String eventType,
        AlertSeverity severity,
        String title,
        String summary,
        String bizNo,
        String traceId,
        AlertDeliveryStatus status,
        Integer httpStatus,
        int attemptCount,
        String errorMessage,
        Instant createdAt) {

    public static AlertDeliveryResponse from(AlertDelivery delivery) {
        return new AlertDeliveryResponse(
                delivery.getId(),
                delivery.getChannelId(),
                delivery.getChannelName(),
                delivery.getChannelType(),
                delivery.getEventType(),
                delivery.getSeverity(),
                delivery.getTitle(),
                delivery.getSummary(),
                delivery.getBizNo(),
                delivery.getTraceId(),
                delivery.getStatus(),
                delivery.getHttpStatus(),
                delivery.getAttemptCount(),
                delivery.getErrorMessage(),
                delivery.getCreatedAt());
    }
}
