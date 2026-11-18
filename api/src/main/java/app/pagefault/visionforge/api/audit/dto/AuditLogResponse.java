package app.pagefault.visionforge.api.audit.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.api.audit.AuditLogEntity;

public record AuditLogResponse(
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

    public static AuditLogResponse from(AuditLogEntity entity) {
        return new AuditLogResponse(
                entity.getId(),
                entity.getType(),
                entity.getSubType(),
                entity.getBizNo(),
                entity.getOperatorId(),
                entity.getOperatorName(),
                entity.isSuccess(),
                entity.getDetail(),
                entity.getExtra(),
                entity.getTraceId(),
                entity.getErrorMessage(),
                entity.getCreatedAt());
    }
}
