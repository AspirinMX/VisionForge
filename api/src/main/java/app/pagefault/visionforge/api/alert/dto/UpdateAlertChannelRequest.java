package app.pagefault.visionforge.api.alert.dto;

import app.pagefault.visionforge.agent.alert.AlertChannelType;
import app.pagefault.visionforge.common.alert.AlertSeverity;

import jakarta.validation.constraints.Size;

public record UpdateAlertChannelRequest(
        @Size(max = 100, message = "渠道名称最多 100 个字符")
        String name,

        AlertChannelType channelType,

        @Size(max = 1000, message = "目标地址最多 1000 个字符")
        String target,

        @Size(max = 500, message = "签名密钥最多 500 个字符")
        String secret,

        @Size(max = 1000, message = "订阅事件列表过长")
        String subscribedEvents,

        AlertSeverity minSeverity,

        Boolean enabled) {
}
