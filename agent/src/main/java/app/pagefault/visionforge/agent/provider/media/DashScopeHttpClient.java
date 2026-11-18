package app.pagefault.visionforge.agent.provider.media;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin HTTP client for the DashScope media endpoints. The official SDK keeps its base URL in a
 * process-wide static field, which would prevent per-model routing, so adapters speak the
 * documented JSON contract directly. The client is stateless and safe to share.
 */
@RequiredArgsConstructor
class DashScopeHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    JsonNode post(MediaProviderRequest request, String path, JsonNode body, boolean async) {
        HttpRequest.Builder builder = requestBuilder(request, path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8));
        if (async) {
            builder.header("X-DashScope-Async", "enable");
        }
        return execute(builder.build());
    }

    JsonNode get(MediaProviderRequest request, String path) {
        return execute(requestBuilder(request, path).GET().build());
    }

    private HttpRequest.Builder requestBuilder(MediaProviderRequest request, String path) {
        String baseUrl = requireText(request.baseUrl(), "百炼 baseUrl 未配置");
        String apiKey = requireText(request.apiKey(), "百炼 API Key 未配置");
        String endpoint = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + path
                : baseUrl + path;
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw failure("百炼请求地址不合法：" + endpoint);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw failure("百炼请求地址必须是 HTTP 或 HTTPS");
        }
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json");
    }

    private JsonNode execute(HttpRequest request) {
        try {
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String payload = new String(response.body(), StandardCharsets.UTF_8);
            JsonNode body = payload.isBlank() ? objectMapper.createObjectNode() : read(payload);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure(errorMessage(body, response.statusCode()));
            }
            return body;
        } catch (IOException exception) {
            throw failure("百炼请求失败：" + message(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("百炼请求被中断");
        }
    }

    private JsonNode read(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (RuntimeException exception) {
            throw failure("百炼返回了无法解析的响应");
        }
    }

    private String errorMessage(JsonNode body, int status) {
        String code = body.path("code").asText("");
        String message = body.path("message").asText("");
        String requestId = body.path("request_id").asText("");
        StringBuilder text = new StringBuilder("百炼调用失败 HTTP ").append(status);
        if (!code.isBlank()) {
            text.append(' ').append(code);
        }
        if (!message.isBlank()) {
            text.append("：").append(message);
        }
        if (!requestId.isBlank()) {
            text.append("（request_id=").append(requestId).append('）');
        }
        return text.toString();
    }

    private String write(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (RuntimeException exception) {
            throw failure("百炼请求体无法序列化");
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw failure(message);
        }
        return value;
    }

    private BusinessException failure(String message) {
        return new BusinessException(AgentErrorCode.GENERATION_PROVIDER_ERROR, message);
    }

    private String message(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
