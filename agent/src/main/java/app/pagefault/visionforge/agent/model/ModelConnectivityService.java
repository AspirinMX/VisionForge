package app.pagefault.visionforge.agent.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import app.pagefault.visionforge.agent.provider.ModelInputItem;
import app.pagefault.visionforge.agent.provider.ModelRequest;
import app.pagefault.visionforge.agent.provider.ModelStreamListener;
import app.pagefault.visionforge.agent.provider.ResponsesModelClient;
import app.pagefault.visionforge.common.audit.BizLog;

/**
 * Probes connectivity and authentication against a configured model gateway without exposing secrets.
 */
public class ModelConnectivityService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final int DETAIL_MAX_LENGTH = 300;

    private final ModelDefinitionService modelDefinitionService;
    private final ResponsesModelClient responsesModelClient;
    private final HttpClient httpClient;
    private final String userAgent;

    public ModelConnectivityService(
            ModelDefinitionService modelDefinitionService,
            ResponsesModelClient responsesModelClient,
            String userAgent) {
        this(
                modelDefinitionService,
                responsesModelClient,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                userAgent);
    }

    ModelConnectivityService(
            ModelDefinitionService modelDefinitionService,
            ResponsesModelClient responsesModelClient,
            HttpClient httpClient,
            String userAgent) {
        this.modelDefinitionService = modelDefinitionService;
        this.responsesModelClient = responsesModelClient;
        this.httpClient = httpClient;
        this.userAgent = userAgent == null ? "" : userAgent.trim();
    }

    @BizLog(
            type = "AGENT_MODEL",
            subType = "TEST_CONNECTION",
            bizNo = "#modelId",
            success = "模型 {{#modelId}} 连通性探测完成（ok={{#result.ok}}）",
            fail = "模型 {{#modelId}} 连通性探测异常：{{#errorMessage}}",
            sync = true)
    public ProbeResult testConnection(UUID modelId) {
        ModelDefinition model = modelDefinitionService.get(modelId);
        ModelProviderSettings settings = modelDefinitionService.settings(model);
        String protocol = model.getProtocol() == null ? "" : model.getProtocol().name();
        long startedAt = System.nanoTime();

        try {
            URI probeUri = URI.create(settings.baseUrl() + "/models");
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(probeUri)
                    .GET()
                    .timeout(PROBE_TIMEOUT)
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .header("Accept", "application/json");
            if (!userAgent.isEmpty()) {
                requestBuilder.header("User-Agent", userAgent);
            }
            HttpResponse<Void> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            long latencyMs = elapsedMillis(startedAt);

            if (status >= 200 && status < 300) {
                return new ProbeResult(true, true, status, latencyMs, protocol,
                        "模型网关连接与鉴权测试通过（HTTP " + status + "）");
            }
            if (status == 401 || status == 403) {
                return new ProbeResult(false, true, status, latencyMs, protocol,
                        "网关已连通但 API Key 鉴权失败（HTTP " + status + "）");
            }
            if (status == 404 || status == 405) {
                if (model.getCapabilityType() == ModelCapabilityType.CHAT && responsesModelClient != null) {
                    return probeChatResponses(settings, protocol, startedAt);
                }
                return new ProbeResult(true, true, status, latencyMs, protocol,
                        "网关网络可达（HTTP " + status + "，该媒体协议未暴露 /models 端点）");
            }
            return new ProbeResult(false, true, status, latencyMs, protocol,
                    "网关返回异常状态 HTTP " + status);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, false, null, elapsedMillis(startedAt), protocol,
                    "连接探测被中断");
        } catch (IOException | RuntimeException exception) {
            return new ProbeResult(false, false, null, elapsedMillis(startedAt), protocol,
                    truncate("连接失败: " + sanitizeMessage(exception.getMessage(), settings.apiKey())));
        }
    }

    private ProbeResult probeChatResponses(
            ModelProviderSettings settings, String protocol, long startedAt) {
        try {
            ModelRequest probe = new ModelRequest(
                    settings.baseUrl(),
                    settings.apiKey(),
                    settings.modelId(),
                    ReasoningEffort.NONE,
                    16,
                    "Reply with OK.",
                    List.of(new ModelInputItem.Message("user", "ping")),
                    List.of(),
                    null);
            responsesModelClient.stream(probe, ModelStreamListener.NOOP);
            return new ProbeResult(true, true, 200, elapsedMillis(startedAt), protocol,
                    "Chat Responses 探针调用成功");
        } catch (RuntimeException exception) {
            return new ProbeResult(false, true, null, elapsedMillis(startedAt), protocol,
                    truncate("Responses 调用失败: " + sanitizeMessage(exception.getMessage(), settings.apiKey())));
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static String sanitizeMessage(String message, String apiKey) {
        if (message == null || message.isBlank()) {
            return "未知网络错误";
        }
        String sanitized = message;
        if (apiKey != null && !apiKey.isBlank()) {
            sanitized = sanitized.replace(apiKey, "***");
        }
        return sanitized;
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= DETAIL_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, DETAIL_MAX_LENGTH) + "...";
    }

    public record ProbeResult(
            boolean ok,
            boolean reachable,
            Integer statusCode,
            long latencyMs,
            String protocol,
            String detail) {
    }
}
