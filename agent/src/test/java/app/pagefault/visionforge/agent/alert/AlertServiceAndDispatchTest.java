package app.pagefault.visionforge.agent.alert;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.model.ApiKeyCipher;
import app.pagefault.visionforge.common.alert.AlertEvent;
import app.pagefault.visionforge.common.alert.AlertSeverity;
import app.pagefault.visionforge.common.config.ConfigStore;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertServiceAndDispatchTest {

    private static final UUID ADMIN_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private AlertChannelMapper channelMapper;
    private AlertDeliveryMapper deliveryMapper;
    private ApiKeyCipher apiKeyCipher;
    private DynamicRuntimeConfig runtimeConfig;
    private AlertProperties alertProperties;
    private AlertChannelService channelService;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        channelMapper = mock(AlertChannelMapper.class);
        deliveryMapper = mock(AlertDeliveryMapper.class);
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        apiKeyCipher = new ApiKeyCipher(key);
        runtimeConfig = new DynamicRuntimeConfig(
                new InMemoryConfigStore(), 0, Clock.fixed(NOW, ZoneOffset.UTC));
        runtimeConfig.init();
        alertProperties = new AlertProperties();
        IdGenerator idGenerator = UUID::randomUUID;
        channelService = new AlertChannelService(
                channelMapper,
                deliveryMapper,
                apiKeyCipher,
                idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                alertProperties,
                runtimeConfig);
        httpClient = mock(HttpClient.class);
        objectMapper = JsonMapper.builder().build();
    }

    @Test
    void webhookTargetValidatorRejectsLoopbackAndLinkLocalWhenPrivateDisallowed() {
        assertThatThrownBy(() -> WebhookTargetValidator.validateAndParse("http://127.0.0.1:8080/hook", false))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(AgentErrorCode.ALERT_CONFIG_INVALID));

        assertThatThrownBy(() -> WebhookTargetValidator.validateAndParse("http://169.254.169.254/latest", false))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(AgentErrorCode.ALERT_CONFIG_INVALID));

        assertThat(WebhookTargetValidator.validateAndParse("https://8.8.8.8/webhook", false).getHost())
                .isEqualTo("8.8.8.8");
        assertThat(WebhookTargetValidator.validateAndParse("http://127.0.0.1:8080/hook", true).getPort())
                .isEqualTo(8080);
    }

    @Test
    void createsEmailAndEncryptedWebhookChannelsCleanly() {
        AlertChannel emailChannel = channelService.createChannel(
                ADMIN_ID,
                new AlertChannelService.ChannelInput(
                        "Ops Team",
                        AlertChannelType.EMAIL,
                        " ops@example.com , oncall@example.com ",
                        null,
                        "ARCHIVE_FAILED, WORKFLOW_START_EXHAUSTED",
                        AlertSeverity.CRITICAL,
                        true));
        assertThat(emailChannel.getTarget()).isEqualTo("ops@example.com,oncall@example.com");
        assertThat(emailChannel.getSecretCiphertext()).isNull();
        verify(channelMapper).insert(emailChannel);

        AlertChannel webhookChannel = channelService.createChannel(
                ADMIN_ID,
                new AlertChannelService.ChannelInput(
                        "Pager Webhook",
                        AlertChannelType.WEBHOOK,
                        "https://8.8.8.8/alerts",
                        "whsec_super_secret",
                        null,
                        null,
                        true));
        assertThat(webhookChannel.getSecretCiphertext()).isNotBlank().doesNotContain("whsec_super_secret");
        assertThat(apiKeyCipher.decrypt(webhookChannel.getSecretCiphertext())).isEqualTo("whsec_super_secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchesSignedWebhookWithRetriesAndSendsEmailWithDynamicSmtp() throws Exception {
        AlertChannel webhookChannel = new AlertChannel();
        webhookChannel.setId(UUID.randomUUID());
        webhookChannel.setName("Webhook");
        webhookChannel.setChannelType(AlertChannelType.WEBHOOK);
        webhookChannel.setTarget("https://8.8.8.8/hook");
        webhookChannel.setSecretCiphertext(apiKeyCipher.encrypt("hmac-secret"));
        webhookChannel.setSubscribedEvents("*");
        webhookChannel.setMinSeverity(AlertSeverity.WARNING);
        webhookChannel.setEnabled(true);

        AlertChannel emailChannel = new AlertChannel();
        emailChannel.setId(UUID.randomUUID());
        emailChannel.setName("Ops Mail");
        emailChannel.setChannelType(AlertChannelType.EMAIL);
        emailChannel.setTarget("ops@example.com");
        emailChannel.setSubscribedEvents(AlertEvent.EVENT_GENERATION_ARCHIVE_FAILED);
        emailChannel.setMinSeverity(AlertSeverity.CRITICAL);
        emailChannel.setEnabled(true);

        when(channelMapper.selectList(any())).thenReturn(List.of(webhookChannel, emailChannel));

        HttpResponse<Object> serverError = mock(HttpResponse.class);
        when(serverError.statusCode()).thenReturn(502);
        HttpResponse<Object> okResponse = mock(HttpResponse.class);
        when(okResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any()))
                .thenReturn(serverError)
                .thenReturn(okResponse);

        runtimeConfig.publish(
                null,
                DynamicRuntimeConfig.DATA_ID_ALERT,
                "{\"alertsEnabled\":true,\"smtpHost\":\"smtp.example.com\",\"smtpPort\":587,\"smtpFrom\":\"noreply@example.com\"}");

        AtomicInteger mailCount = new AtomicInteger();
        AtomicReference<String> lastSubject = new AtomicReference<>();
        AlertDispatchService.MailSender mailStub = (cfg, to, subject, body) -> {
            assertThat(cfg.host()).isEqualTo("smtp.example.com");
            assertThat(to).containsExactly("ops@example.com");
            lastSubject.set(subject);
            mailCount.incrementAndGet();
        };

        AlertDispatchService dispatch = new AlertDispatchService(
                channelMapper,
                deliveryMapper,
                channelService,
                apiKeyCipher,
                UUID::randomUUID,
                Clock.fixed(NOW, ZoneOffset.UTC),
                objectMapper,
                alertProperties,
                runtimeConfig,
                httpClient,
                mailStub,
                new DirectExecutorService());

        dispatch.publish(AlertEvent.of(
                AlertEvent.EVENT_GENERATION_ARCHIVE_FAILED,
                AlertSeverity.CRITICAL,
                "agent",
                "归档失败",
                "写入 RustFS 超时",
                "task-123"));

        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(2)).send(reqCaptor.capture(), any());
        assertThat(reqCaptor.getValue().headers().firstValue("X-VisionForge-Signature"))
                .isPresent()
                .get()
                .asString()
                .startsWith("sha256=");
        assertThat(mailCount.get()).isEqualTo(1);
        assertThat(lastSubject.get()).contains("CRITICAL").contains("归档失败");
        verify(deliveryMapper, times(2)).insert(any(AlertDelivery.class));
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class InMemoryConfigStore implements ConfigStore {

        private final Map<String, StoredConfig> rows = new ConcurrentHashMap<>();

        @Override
        public void ensure(String dataId, String content) {
            rows.putIfAbsent(dataId, new StoredConfig(content, 1, NOW));
        }

        @Override
        public Map<String, StoredConfig> loadAll() {
            return Map.copyOf(rows);
        }

        @Override
        public Map<String, Long> versions() {
            Map<String, Long> versions = new LinkedHashMap<>();
            rows.forEach((dataId, row) -> versions.put(dataId, row.version()));
            return versions;
        }

        @Override
        public StoredConfig save(String dataId, String content, UUID updatedBy) {
            StoredConfig current = rows.get(dataId);
            long nextVersion = current == null ? 1 : current.version() + 1;
            StoredConfig saved = new StoredConfig(content, nextVersion, NOW);
            rows.put(dataId, saved);
            return saved;
        }
    }
}
