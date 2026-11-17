package app.pagefault.visionforge.billing.credit;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Per-user credit balance. {@code reservedUnits} is held against future settlement and cannot be
 * reserved twice.
 */
@Getter
@Setter
@TableName("credit_account")
public class CreditAccount {

    @TableId(value = "user_id", type = IdType.INPUT)
    private UUID userId;

    private long balanceUnits;

    private long reservedUnits;

    private long version;

    private Instant createdAt;

    private Instant updatedAt;
}
