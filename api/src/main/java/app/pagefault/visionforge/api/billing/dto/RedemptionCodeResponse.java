package app.pagefault.visionforge.api.billing.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.billing.credit.CreditRedemptionCode;
import app.pagefault.visionforge.billing.credit.RedemptionCodeStatus;

public record RedemptionCodeResponse(
        UUID id,
        String batchName,
        String code,
        long amountUnits,
        int maxUses,
        int redeemedCount,
        int perUserLimit,
        RedemptionCodeStatus status,
        Instant expiresAt,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static RedemptionCodeResponse from(CreditRedemptionCode entity) {
        return new RedemptionCodeResponse(
                entity.getId(),
                entity.getBatchName(),
                entity.getCode(),
                entity.getAmountUnits(),
                entity.getMaxUses(),
                entity.getRedeemedCount(),
                entity.getPerUserLimit(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
