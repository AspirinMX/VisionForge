package app.pagefault.visionforge.api.account.dto;

import java.time.Instant;
import java.util.UUID;

import app.pagefault.visionforge.agent.generation.GenerationStatus;
import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.billing.credit.CreditLedgerEntryType;

/**
 * One append-only ledger row. The entry type carries the accounting meaning: GRANT increases the
 * balance, RESERVE only holds units, SETTLE deducts held units and RELEASE returns them.
 * {@code taskId} is resolved through the reservation and is null for grants or detached rows.
 */
public record CreditLedgerEntryResponse(
        UUID id,
        CreditLedgerEntryType entryType,
        long amountUnits,
        UUID reservationId,
        UUID taskId,
        GenerationType taskType,
        GenerationStatus taskStatus,
        Instant createdAt) {
}
