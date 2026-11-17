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
@TableName("credit_reservation")
public class CreditReservation {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private String businessKey;

    private UUID taskId;

    private long amountUnits;

    private CreditReservationStatus status;

    private Instant createdAt;

    private Instant settledAt;
}
