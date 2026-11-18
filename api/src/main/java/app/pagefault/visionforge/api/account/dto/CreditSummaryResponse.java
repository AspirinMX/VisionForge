package app.pagefault.visionforge.api.account.dto;

import java.time.Instant;

import app.pagefault.visionforge.billing.credit.CreditAccount;

/**
 * Current credit balance in the smallest billing unit. {@code reservedUnits} is held for running
 * tasks and is not available for new reservations.
 */
public record CreditSummaryResponse(
        long balanceUnits,
        long reservedUnits,
        long availableUnits,
        Instant updatedAt) {

    public static CreditSummaryResponse from(CreditAccount account) {
        return new CreditSummaryResponse(
                account.getBalanceUnits(),
                account.getReservedUnits(),
                account.getBalanceUnits() - account.getReservedUnits(),
                account.getUpdatedAt());
    }
}
