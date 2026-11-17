package app.pagefault.visionforge.common.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable audit record. {@code detail} is the rendered template; {@code extra} is an
 * optional already-masked JSON snapshot.
 */
public record BizLogRecord(
        UUID id,
        String type,
        String subType,
        String bizNo,
        UUID operatorId,
        String operatorName,
        boolean success,
        String detail,
        String extra,
        String traceId,
        String errorMessage,
        Instant createdAt) {
}
