package app.pagefault.visionforge.billing.credit;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Append-only credit ledger. {@code businessKey} is unique so retries cannot double-charge.
 */
@Getter
@Setter
@TableName("credit_ledger")
public class CreditLedger {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private UUID reservationId;

    private CreditLedgerEntryType entryType;

    private long amountUnits;

    private String businessKey;

    private Instant createdAt;
}
