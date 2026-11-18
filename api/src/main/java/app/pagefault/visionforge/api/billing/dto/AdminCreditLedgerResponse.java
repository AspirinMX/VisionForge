package app.pagefault.visionforge.api.billing.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.billing.credit.CreditLedger;
import app.pagefault.visionforge.billing.credit.CreditLedgerEntryType;

public record AdminCreditLedgerResponse(
        UUID id,
        UUID userId,
        UUID reservationId,
        CreditLedgerEntryType entryType,
        long amountUnits,
        String businessKey,
        String remark,
        Instant createdAt) {

    public static AdminCreditLedgerResponse from(CreditLedger ledger) {
        return new AdminCreditLedgerResponse(
                ledger.getId(),
                ledger.getUserId(),
                ledger.getReservationId(),
                ledger.getEntryType(),
                ledger.getAmountUnits(),
                ledger.getBusinessKey(),
                ledger.getRemark(),
                ledger.getCreatedAt());
    }
}
