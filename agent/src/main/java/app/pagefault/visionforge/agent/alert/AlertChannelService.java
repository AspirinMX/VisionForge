package app.pagefault.visionforge.agent.alert;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ApiKeyCipher;
import app.pagefault.visionforge.common.alert.AlertSeverity;
import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import cn.hutool.core.lang.Validator;
import lombok.RequiredArgsConstructor;

/**
 * Manages persisted alert channels (Email / Webhook) and queries delivery logs.
 */
@RequiredArgsConstructor
public class AlertChannelService {

    private static final int NAME_MAX_LENGTH = 100;
    private static final int TARGET_MAX_LENGTH = 1_000;

    private final AlertChannelMapper channelMapper;
    private final AlertDeliveryMapper deliveryMapper;
    private final ApiKeyCipher apiKeyCipher;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final AlertProperties alertProperties;
    private final DynamicRuntimeConfig runtimeConfig;

    public List<AlertChannel> listChannels() {
        return channelMapper.selectList(
                Wrappers.<AlertChannel>lambdaQuery().orderByAsc(AlertChannel::getCreatedAt));
    }

    public AlertChannel getChannel(UUID id) {
        AlertChannel channel = id == null ? null : channelMapper.selectById(id);
        if (channel == null) {
            throw new BusinessException(AgentErrorCode.ALERT_CHANNEL_NOT_FOUND);
        }
        return channel;
    }

    @Transactional
    @BizLog(
            type = "ADMIN_ALERT",
            subType = "CREATE_CHANNEL",
            bizNo = "#result.id",
            success = "管理员创建告警渠道「{{#result.name}}」（类型={{#result.channelType}}）",
            fail = "创建告警渠道失败：{{#errorMessage}}",
            sync = true)
    public AlertChannel createChannel(UUID actorId, ChannelInput input) {
        if (input == null || input.channelType() == null) {
            throw new BusinessException(AgentErrorCode.ALERT_CONFIG_INVALID, "告警渠道类型不能为空");
        }
        String name = normalizeName(input.name());
        String target = validateTarget(input.channelType(), input.target());
        String events = normalizeEvents(input.subscribedEvents());
        AlertSeverity severity = input.minSeverity() == null ? AlertSeverity.WARNING : input.minSeverity();
        boolean enabled = input.enabled() == null || input.enabled();

        Instant now = clock.instant();
        AlertChannel channel = new AlertChannel();
        channel.setId(idGenerator.nextId());
        channel.setName(name);
        channel.setChannelType(input.channelType());
        channel.setTarget(target);
        if (input.channelType() == AlertChannelType.WEBHOOK
                && input.secret() != null
                && !input.secret().isBlank()) {
            channel.setSecretCiphertext(apiKeyCipher.encrypt(input.secret().trim()));
        }
        channel.setSubscribedEvents(events);
        channel.setMinSeverity(severity);
        channel.setEnabled(enabled);
        channel.setCreatedBy(actorId);
        channel.setCreatedAt(now);
        channel.setUpdatedAt(now);
        channelMapper.insert(channel);
        return channel;
    }

    @Transactional
    @BizLog(
            type = "ADMIN_ALERT",
            subType = "UPDATE_CHANNEL",
            bizNo = "#id",
            success = "管理员更新告警渠道「{{#result.name}}」",
            fail = "更新告警渠道 {{#id}} 失败：{{#errorMessage}}",
            sync = true)
    public AlertChannel updateChannel(UUID id, ChannelInput input) {
        AlertChannel channel = getChannel(id);
        if (input == null) {
            return channel;
        }
        if (input.name() != null) {
            channel.setName(normalizeName(input.name()));
        }
        AlertChannelType effectiveType = input.channelType() == null ? channel.getChannelType() : input.channelType();
        channel.setChannelType(effectiveType);
        if (input.target() != null) {
            channel.setTarget(validateTarget(effectiveType, input.target()));
        } else if (input.channelType() != null) {
            channel.setTarget(validateTarget(effectiveType, channel.getTarget()));
        }
        if (effectiveType == AlertChannelType.EMAIL) {
            channel.setSecretCiphertext(null);
        } else if (input.secret() != null) {
            channel.setSecretCiphertext(input.secret().isBlank()
                    ? null
                    : apiKeyCipher.encrypt(input.secret().trim()));
        }
        if (input.subscribedEvents() != null) {
            channel.setSubscribedEvents(normalizeEvents(input.subscribedEvents()));
        }
        if (input.minSeverity() != null) {
            channel.setMinSeverity(input.minSeverity());
        }
        if (input.enabled() != null) {
            channel.setEnabled(input.enabled());
        }
        channel.setUpdatedAt(clock.instant());
        channelMapper.updateById(channel);
        return channel;
    }

    @Transactional
    @BizLog(
            type = "ADMIN_ALERT",
            subType = "DELETE_CHANNEL",
            bizNo = "#id",
            success = "管理员删除告警渠道 {{#id}}",
            fail = "删除告警渠道 {{#id}} 失败：{{#errorMessage}}",
            sync = true)
    public void deleteChannel(UUID id) {
        getChannel(id);
        channelMapper.deleteById(id);
    }

    public PageResult<AlertDelivery> listDeliveries(
            long page,
            long size,
            UUID channelId,
            AlertDeliveryStatus status,
            String eventType) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 200);
        LambdaQueryWrapper<AlertDelivery> wrapper = Wrappers.<AlertDelivery>lambdaQuery()
                .eq(channelId != null, AlertDelivery::getChannelId, channelId)
                .eq(status != null, AlertDelivery::getStatus, status)
                .eq(eventType != null && !eventType.isBlank(), AlertDelivery::getEventType,
                        eventType == null ? null : eventType.trim())
                .orderByDesc(AlertDelivery::getCreatedAt)
                .orderByDesc(AlertDelivery::getId);
        Page<AlertDelivery> result = deliveryMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result.getRecords(), result.getCurrent(), result.getSize(), result.getTotal());
    }

    private String normalizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(AgentErrorCode.ALERT_CONFIG_INVALID, "渠道名称长度必须在 1 到 100 之间");
        }
        return trimmed;
    }

    private String validateTarget(AlertChannelType type, String rawTarget) {
        String trimmed = rawTarget == null ? "" : rawTarget.trim();
        if (trimmed.isEmpty() || trimmed.length() > TARGET_MAX_LENGTH) {
            throw new BusinessException(AgentErrorCode.ALERT_CONFIG_INVALID, "告警目标不能为空且不能超过 1000 个字符");
        }
        if (type == AlertChannelType.EMAIL) {
            List<String> recipients = Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
            if (recipients.isEmpty()) {
                throw new BusinessException(AgentErrorCode.ALERT_CONFIG_INVALID, "至少需要一个收件人邮箱");
            }
            for (String email : recipients) {
                if (!Validator.isEmail(email)) {
                    throw new BusinessException(AgentErrorCode.ALERT_CONFIG_INVALID, "收件人邮箱格式不正确: " + email);
                }
            }
            return String.join(",", recipients);
        }
        boolean allowPrivate = runtimeConfig == null
                ? alertProperties.isWebhookAllowPrivateNetwork()
                : runtimeConfig.webhookAllowPrivateNetwork(alertProperties.isWebhookAllowPrivateNetwork());
        WebhookTargetValidator.validateAndParse(trimmed, allowPrivate);
        return trimmed;
    }

    private String normalizeEvents(String subscribedEvents) {
        if (subscribedEvents == null || subscribedEvents.isBlank()) {
            return "*";
        }
        String joined = Arrays.stream(subscribedEvents.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? "*" : joined;
    }

    public record ChannelInput(
            String name,
            AlertChannelType channelType,
            String target,
            String secret,
            String subscribedEvents,
            AlertSeverity minSeverity,
            Boolean enabled) {
    }
}
