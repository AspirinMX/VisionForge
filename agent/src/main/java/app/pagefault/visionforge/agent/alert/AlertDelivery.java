package app.pagefault.visionforge.agent.alert;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import app.pagefault.visionforge.common.alert.AlertSeverity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("alert_delivery")
public class AlertDelivery {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID channelId;

    private String channelName;

    private AlertChannelType channelType;

    private String eventType;

    private AlertSeverity severity;

    private String title;

    private String summary;

    private String bizNo;

    private String traceId;

    private AlertDeliveryStatus status;

    private Integer httpStatus;

    private int attemptCount;

    private String errorMessage;

    private Instant createdAt;
}
