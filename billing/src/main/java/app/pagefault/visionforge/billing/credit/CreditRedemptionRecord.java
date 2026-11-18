package app.pagefault.visionforge.billing.credit;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("credit_redemption_record")
public class CreditRedemptionRecord {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID codeId;

    private UUID userId;

    private UUID ledgerId;

    private long amountUnits;

    private Instant createdAt;
}
