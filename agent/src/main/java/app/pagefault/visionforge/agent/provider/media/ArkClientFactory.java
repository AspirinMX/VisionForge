package app.pagefault.visionforge.agent.provider.media;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless HTTP transport for Volcengine Ark media endpoints. Using JDK HttpClient with Jackson 3
 * avoids the legacy ark-runtime SDK dependency on removed Jackson 2 PropertyNamingStrategy fields.
 */
public class ArkClientFactory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(15);
    private static final String API_V3_PREFIX = "/api/v3";

    private final String userAgent;
    private final HttpClient client;

    public ArkClientFactory() {
        this("");
    }

    public ArkClientFactory(String userAgent) {
        this(userAgent, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    ArkClientFactory(String userAgent, HttpClient client) {
        this.userAgent = userAgent == null ? "" : userAgent.trim();
        this.client = client;
    }

    public Map<String, String> customHeaders() {
        return userAgent.isBlank() ? Map.of() : Map.of("User-Agent", userAgent);
    }

    JsonNode post(
            MediaProviderRequest request,
            String path,
            JsonNode body,
            ObjectMapper objectMapper) {
        HttpRequest httpRequest = requestBuilder(request, path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        write(objectMapper, body), StandardCharsets.UTF_8))
                .build();
        return execute(httpRequest, objectMapper);
    }

    JsonNode get(MediaProviderRequest request, String path, ObjectMapper objectMapper) {
        HttpRequest httpRequest = requestBuilder(request, path).GET().build();
        return execute(httpRequest, objectMapper);
    }

    URI resolveEndpoint(String rawBaseUrl, String apiPath) {
        String baseUrl = requireText(rawBaseUrl, "Ark baseUrl 未配置");
        String normalized = baseUrl.replaceAll("/+$", "");
        String resolvedPath = normalized.endsWith(API_V3_PREFIX) && apiPath.startsWith(API_V3_PREFIX + "/")
                ? normalized + apiPath.substring(API_V3_PREFIX.length())
                : normalized + apiPath;
        URI uri;
        try {
            uri = URI.create(resolvedPath);
        } catch (IllegalArgumentException exception) {
            throw failure("Ark 请求地址不合法：" + resolvedPath);
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw failure("Ark 请求地址必须是 HTTP 或 HTTPS");
        }
        return uri;
    }

    private HttpRequest.Builder requestBuilder(MediaProviderRequest request, String path) {
        String apiKey = requireText(request.apiKey(), "Ark API Key 未配置");
        URI uri = resolveEndpoint(request.baseUrl(), path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json");
        if (!userAgent.isBlank()) {
            builder.header("User-Agent", userAgent);
        }
        return builder;
    }

    private JsonNode execute(HttpRequest httpRequest, ObjectMapper objectMapper) {
        try {
            HttpResponse<byte[]> response =
                    client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            String payload = new String(response.body(), StandardCharsets.UTF_8);
            JsonNode body = payload.isBlank()
                    ? objectMapper.createObjectNode()
                    : read(objectMapper, payload);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure(errorMessage(body, response.statusCode()));
            }
            return body;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure("Ark 请求失败：" + message(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("Ark 请求被中断");
        }
    }

    private JsonNode read(ObjectMapper objectMapper, String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (RuntimeException exception) {
            throw failure("Ark 返回了无法解析的响应");
        }
    }

    private String write(ObjectMapper objectMapper, JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (RuntimeException exception) {
            throw failure("Ark 请求体无法序列化");
        }
    }

    private String errorMessage(JsonNode body, int status) {
        JsonNode errorNode = body.path("error");
        String code = errorNode.path("code").asText(body.path("code").asText(""));
        String message = errorNode.path("message").asText(body.path("message").asText(""));
        StringBuilder text = new StringBuilder("Ark 调用失败 HTTP ").append(status);
        if (!code.isBlank()) {
            text.append(' ').append(code);
        }
        if (!message.isBlank()) {
            text.append("：").append(message);
        }
        return text.toString();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw failure(message);
        }
        return value.trim();
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
