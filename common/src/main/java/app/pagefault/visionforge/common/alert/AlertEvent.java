package app.pagefault.visionforge.common.alert;

import java.time.Instant;
import java.util.Map;

import app.pagefault.visionforge.common.logging.TraceContext;

/**
 * Immutable operational or security alert emitted across API and Worker modules.
 */
public record AlertEvent(
        String eventType,
        AlertSeverity severity,
        String source,
        String title,
        String summary,
        String bizNo,
        String traceId,
        Instant occurredAt,
        Map<String, Object> metadata) {

    public static final String EVENT_WORKFLOW_START_EXHAUSTED = "WORKFLOW_START_EXHAUSTED";
    public static final String EVENT_MODEL_PROVIDER_ERROR = "MODEL_PROVIDER_ERROR";
    public static final String EVENT_MEDIA_PROVIDER_ERROR = "MEDIA_PROVIDER_ERROR";
    public static final String EVENT_GENERATION_ARCHIVE_FAILED = "GENERATION_ARCHIVE_FAILED";
    public static final String EVENT_BILLING_PENDING_RECONCILIATION = "BILLING_PENDING_RECONCILIATION";
    public static final String EVENT_COMPOSITION_FAILED = "COMPOSITION_FAILED";
    public static final String EVENT_ADMIN_SECURITY_CHANGE = "ADMIN_SECURITY_CHANGE";
    public static final String EVENT_SYSTEM_INTERNAL_ERROR = "SYSTEM_INTERNAL_ERROR";
    public static final String EVENT_ALERT_TEST = "ALERT_TEST";

    public AlertEvent {
        severity = severity == null ? AlertSeverity.WARNING : severity;
        source = (source == null || source.isBlank()) ? "visionforge" : source.trim();
        traceId = (traceId == null || traceId.isBlank()) ? TraceContext.currentTraceId() : traceId;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AlertEvent of(
            String eventType,
            AlertSeverity severity,
            String source,
            String title,
            String summary,
            String bizNo) {
        return new AlertEvent(
                eventType,
                severity,
                source,
                title,
                summary,
                bizNo,
                TraceContext.currentTraceId(),
                Instant.now(),
                Map.of());
    }
}
