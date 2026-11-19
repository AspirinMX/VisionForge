package app.pagefault.visionforge.agent.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.model.ApiKeyCipher;
import app.pagefault.visionforge.common.alert.AlertEvent;
import app.pagefault.visionforge.common.alert.AlertPublisher;
import app.pagefault.visionforge.common.alert.AlertSeverity;
import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Dispatches Email (SMTP) and signed Webhook alerts asynchronously without blocking business flows.
 */
@Slf4j
public class AlertDispatchService implements AlertPublisher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_WEBHOOK_ATTEMPTS = 3;
    private static final int ERROR_MAX_LENGTH = 500;

    private final AlertChannelMapper channelMapper;
    private final AlertDeliveryMapper deliveryMapper;
    private final AlertChannelService channelService;
    private final ApiKeyCipher apiKeyCipher;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final AlertProperties alertProperties;
    private final DynamicRuntimeConfig runtimeConfig;
    private final HttpClient httpClient;
    private final MailSender mailSender;
    private final ExecutorService executor;

    public AlertDispatchService(
            AlertChannelMapper channelMapper,
            AlertDeliveryMapper deliveryMapper,
            AlertChannelService channelService,
            ApiKeyCipher apiKeyCipher,
            IdGenerator idGenerator,
            Clock clock,
            ObjectMapper objectMapper,
            AlertProperties alertProperties,
            DynamicRuntimeConfig runtimeConfig) {
        this(
                channelMapper,
                deliveryMapper,
                channelService,
                apiKeyCipher,
                idGenerator,
                clock,
                objectMapper,
                alertProperties,
                runtimeConfig,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                AlertDispatchService::sendViaSmtp,
                new ThreadPoolExecutor(
                        1,
                        4,
                        60L,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(512),
                        runnable -> {
                            Thread thread = new Thread(runnable, "vf-alert-dispatch");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.DiscardOldestPolicy()));
    }

    AlertDispatchService(
            AlertChannelMapper channelMapper,
            AlertDeliveryMapper deliveryMapper,
            AlertChannelService channelService,
            ApiKeyCipher apiKeyCipher,
            IdGenerator idGenerator,
            Clock clock,
            ObjectMapper objectMapper,
            AlertProperties alertProperties,
            DynamicRuntimeConfig runtimeConfig,
            HttpClient httpClient,
            MailSender mailSender,
            ExecutorService executor) {
        this.channelMapper = channelMapper;
        this.deliveryMapper = deliveryMapper;
        this.channelService = channelService;
        this.apiKeyCipher = apiKeyCipher;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.alertProperties = alertProperties;
        this.runtimeConfig = runtimeConfig;
        this.httpClient = httpClient;
        this.mailSender = mailSender;
        this.executor = executor;
    }

    @Override
    public void publish(AlertEvent event) {
        if (event == null || !isAlertingEnabled()) {
            return;
        }
        executor.execute(() -> dispatchEvent(event));
    }

    @BizLog(
            type = "ADMIN_ALERT",
            subType = "TEST_CHANNEL",
            bizNo = "#channelId",
            success = "管理员发送测试告警到渠道 {{#channelId}}（状态={{#result.status}}）",
            fail = "测试告警渠道 {{#channelId}} 失败：{{#errorMessage}}",
            sync = true)
    public AlertDelivery testChannel(UUID channelId) {
        AlertChannel channel = channelService.getChannel(channelId);
        AlertEvent testEvent = AlertEvent.of(
                AlertEvent.EVENT_ALERT_TEST,
                AlertSeverity.INFO,
                "visionforge-admin",
                "VisionForge 告警通道连通性测试",
                "这是一条由管理员手动触发的告警通道测试消息，用于验证邮件 SMTP 与 Webhook 签名推送可用性。",
                channelId.toString());
        return deliverSingle(channel, testEvent);
    }

    public void close() {
        executor.shutdown();
    }

    void dispatchEvent(AlertEvent event) {
        if (!isAlertingEnabled()) {
            return;
        }
        try {
            List<AlertChannel> enabledChannels = channelMapper.selectList(
                    Wrappers.<AlertChannel>lambdaQuery().eq(AlertChannel::isEnabled, true));
            for (AlertChannel channel : enabledChannels) {
                if (!matchesChannel(channel, event)) {
                    continue;
                }
                deliverSingle(channel, event);
            }
        } catch (RuntimeException exception) {
            log.warn("Alert dispatch loop failed for event={}: {}", event.eventType(), exception.getMessage());
        }
    }

    AlertDelivery deliverSingle(AlertChannel channel, AlertEvent event) {
        AlertDelivery delivery = new AlertDelivery();
        delivery.setId(idGenerator.nextId());
        delivery.setChannelId(channel.getId());
        delivery.setChannelName(channel.getName());
        delivery.setChannelType(channel.getChannelType());
        delivery.setEventType(event.eventType());
        delivery.setSeverity(event.severity());
        delivery.setTitle(truncate(event.title(), 200));
        delivery.setSummary(truncate(event.summary(), 1000));
        delivery.setBizNo(event.bizNo());
        delivery.setTraceId(event.traceId());
        delivery.setAttemptCount(1);
        delivery.setCreatedAt(clock.instant());

        try {
            if (channel.getChannelType() == AlertChannelType.EMAIL) {
                sendEmail(channel, event, delivery);
            } else {
                sendWebhook(channel, event, delivery);
            }
        } catch (RuntimeException exception) {
            delivery.setStatus(AlertDeliveryStatus.FAILED);
            delivery.setErrorMessage(truncate(exception.getMessage(), ERROR_MAX_LENGTH));
        }

        try {
            deliveryMapper.insert(delivery);
        } catch (RuntimeException exception) {
            log.warn("Failed recording alert delivery for channel={}: {}",
                    channel.getId(), exception.getMessage());
        }
        return delivery;
    }

    private void sendEmail(AlertChannel channel, AlertEvent event, AlertDelivery delivery) {
        MailConfig mailConfig = resolveMailConfig();
        if (mailConfig.host().isBlank()) {
            delivery.setStatus(AlertDeliveryStatus.FAILED);
            delivery.setErrorMessage("SMTP host 未配置（请通过 Nacos visionforge-alert.json 或环境变量 ALERT_MAIL_HOST 配置）");
            return;
        }
        String[] recipients = Arrays.stream(channel.getTarget().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        String subject = "[VisionForge][" + event.severity() + "] " + event.title();
        String body = buildTextBody(event);
        mailSender.send(mailConfig, recipients, subject, body);
        delivery.setStatus(AlertDeliveryStatus.SENT);
    }

    private void sendWebhook(AlertChannel channel, AlertEvent event, AlertDelivery delivery) {
        boolean allowPrivate = runtimeConfig == null
                ? alertProperties.isWebhookAllowPrivateNetwork()
                : runtimeConfig.webhookAllowPrivateNetwork(alertProperties.isWebhookAllowPrivateNetwork());
        URI uri = WebhookTargetValidator.validateAndParse(channel.getTarget(), allowPrivate);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", delivery.getId().toString());
        payload.put("eventType", event.eventType());
        payload.put("severity", event.severity().name());
        payload.put("source", event.source());
        payload.put("title", event.title());
        payload.put("summary", event.summary());
        payload.put("bizNo", event.bizNo());
        payload.put("traceId", event.traceId());
        payload.put("occurredAt", event.occurredAt().toString());
        if (!event.metadata().isEmpty()) {
            payload.put("metadata", event.metadata());
        }
        String body = objectMapper.writeValueAsString(payload);
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String signature = computeHmacSignature(channel, timestamp, body);

        Integer lastStatus = null;
        String lastError = null;
        for (int attempt = 1; attempt <= MAX_WEBHOOK_ATTEMPTS; attempt++) {
            delivery.setAttemptCount(attempt);
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("X-VisionForge-Event", event.eventType())
                        .header("X-VisionForge-Timestamp", timestamp);
                if (signature != null) {
                    req.header("X-VisionForge-Signature", signature);
                }
                HttpResponse<Void> response = httpClient.send(
                        req.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                        HttpResponse.BodyHandlers.discarding());
                lastStatus = response.statusCode();
                delivery.setHttpStatus(lastStatus);
                if (lastStatus >= 200 && lastStatus < 300) {
                    delivery.setStatus(AlertDeliveryStatus.SENT);
                    delivery.setErrorMessage(null);
                    return;
                }
                lastError = "Webhook 响应状态 HTTP " + lastStatus;
                if (lastStatus >= 400 && lastStatus < 500) {
                    break;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                lastError = "Webhook 投递被中断";
                break;
            } catch (Exception exception) {
                lastError = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();
            }
            if (attempt < MAX_WEBHOOK_ATTEMPTS) {
                sleepQuietly(200L * attempt);
            }
        }
        delivery.setStatus(AlertDeliveryStatus.FAILED);
        delivery.setHttpStatus(lastStatus);
        delivery.setErrorMessage(truncate(lastError, ERROR_MAX_LENGTH));
    }

    private String computeHmacSignature(AlertChannel channel, String timestamp, String body) {
        if (channel.getSecretCiphertext() == null || channel.getSecretCiphertext().isBlank()) {
            return null;
        }
        String secret = apiKeyCipher.decrypt(channel.getSecretCiphertext());
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not compute HMAC-SHA256 signature", exception);
        }
    }

    private boolean matchesChannel(AlertChannel channel, AlertEvent event) {
        if (!event.severity().satisfies(channel.getMinSeverity())) {
            return false;
        }
        String subscribed = channel.getSubscribedEvents();
        if (subscribed == null || subscribed.isBlank() || "*".equals(subscribed.trim())) {
            return true;
        }
        return Arrays.stream(subscribed.split(","))
                .map(String::trim)
                .anyMatch(pattern -> "*".equals(pattern) || pattern.equalsIgnoreCase(event.eventType()));
    }

    private boolean isAlertingEnabled() {
        return runtimeConfig == null
                ? alertProperties.isEnabled()
                : runtimeConfig.alertsEnabled(alertProperties.isEnabled());
    }

    private MailConfig resolveMailConfig() {
        AlertProperties.Mail mail = alertProperties.getMail();
        String host = runtimeConfig == null ? mail.getHost() : runtimeConfig.smtpHost(mail.getHost());
        int port = runtimeConfig == null ? mail.getPort() : runtimeConfig.smtpPort(mail.getPort());
        String username = runtimeConfig == null ? mail.getUsername() : runtimeConfig.smtpUsername(mail.getUsername());
        String from = runtimeConfig == null ? mail.getFrom() : runtimeConfig.smtpFrom(mail.getFrom());
        boolean ssl = runtimeConfig == null ? mail.isSslEnabled() : runtimeConfig.smtpSslEnabled(mail.isSslEnabled());
        String effectiveFrom = (from == null || from.isBlank()) ? username : from;
        return new MailConfig(
                host == null ? "" : host.trim(),
                port <= 0 ? 587 : port,
                username == null ? "" : username.trim(),
                mail.getPassword() == null ? "" : mail.getPassword(),
                effectiveFrom == null ? "" : effectiveFrom.trim(),
                ssl);
    }

    private static void sendViaSmtp(MailConfig config, String[] to, String subject, String body) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (!config.username().isBlank()) {
            sender.setUsername(config.username());
            sender.setPassword(config.password());
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", Boolean.toString(!config.username().isBlank()));
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.smtp.writetimeout", "8000");
        if (config.sslEnabled()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.ssl.enable", Boolean.toString(config.port() == 465));
        }

        SimpleMailMessage message = new SimpleMailMessage();
        if (!config.from().isBlank()) {
            message.setFrom(config.from());
        }
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }

    private static String buildTextBody(AlertEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("事件类型: ").append(event.eventType()).append('\n');
        sb.append("严重级别: ").append(event.severity()).append('\n');
        sb.append("来源模块: ").append(event.source()).append('\n');
        sb.append("业务编号: ").append(event.bizNo() == null ? "-" : event.bizNo()).append('\n');
        sb.append("Trace ID: ").append(event.traceId() == null ? "-" : event.traceId()).append('\n');
        sb.append("发生时间: ").append(event.occurredAt()).append('\n');
        sb.append('\n').append(event.summary()).append('\n');
        return sb.toString();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    @FunctionalInterface
    interface MailSender {
        void send(MailConfig config, String[] to, String subject, String body);
    }

    record MailConfig(
            String host,
            int port,
            String username,
            String password,
            String from,
            boolean sslEnabled) {
    }
}
