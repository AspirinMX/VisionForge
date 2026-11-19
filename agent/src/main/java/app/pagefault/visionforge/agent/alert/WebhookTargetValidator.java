package app.pagefault.visionforge.agent.alert;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

/**
 * Validates outbound webhook URLs and blocks loopback/private metadata endpoints unless allowed.
 */
public final class WebhookTargetValidator {

    private WebhookTargetValidator() {
    }

    public static URI validateAndParse(String rawUrl, boolean allowPrivateNetwork) {
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        if (trimmed.isEmpty() || trimmed.length() > 500) {
            throw new BusinessException(AgentErrorCode.ALERT_CONFIG_INVALID, "Webhook URL 不能为空且不能超过 500 个字符");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(AgentErrorCode.ALERT_CONFIG_INVALID, "Webhook URL 格式不正确");
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new BusinessException(AgentErrorCode.ALERT_CONFIG_INVALID, "Webhook 仅支持 http 或 https 地址");
        }
        if (allowPrivateNetwork) {
            return uri;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            for (InetAddress address : addresses) {
                if (isRestrictedAddress(address)) {
                    throw new BusinessException(
                            AgentErrorCode.ALERT_CONFIG_INVALID,
                            "出于安全限制，不允许将 Webhook 指向内网或回环地址");
                }
            }
        } catch (UnknownHostException exception) {
            throw new BusinessException(AgentErrorCode.ALERT_CONFIG_INVALID, "无法解析 Webhook 主机名");
        }
        return uri;
    }

    static boolean isRestrictedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }
}
