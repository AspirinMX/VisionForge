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
@TableName("credit_redemption_code")
public class CreditRedemptionCode {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String batchName;

    private String code;

    private long amountUnits;

    private int maxUses;

    private int redeemedCount;

    private int perUserLimit;

    private RedemptionCodeStatus status;

    private Instant expiresAt;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;
}
