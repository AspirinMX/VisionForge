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
@TableName("alert_channel")
public class AlertChannel {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String name;

    private AlertChannelType channelType;

    private String target;

    private String secretCiphertext;

    private String subscribedEvents;

    private AlertSeverity minSeverity;

    private boolean enabled;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;
}
