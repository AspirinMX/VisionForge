package app.pagefault.visionforge.agent.provider.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

/**
 * Bounded downloader for provider result URLs. HTTPS is required unless the development-only
 * {@code MEDIA_ALLOW_HTTP=true} switch is set; redirects are not followed and the response size is
 * capped before decoding.
 */
public class HttpMediaDownloader implements MediaResultDownloader {

    private final long maxBytes;
    private final boolean allowHttp;
    private final HttpClient client;

    public HttpMediaDownloader(long maxBytes) {
        this(maxBytes, allowHttpFromEnvironment());
    }

    HttpMediaDownloader(long maxBytes, boolean allowHttp) {
        this.maxBytes = maxBytes;
        this.allowHttp = allowHttp;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public byte[] download(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw failure("产物 URL 不合法");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        if (!https && !(allowHttp && http)) {
            throw failure(allowHttp ? "产物 URL 必须是 HTTP 或 HTTPS" : "产物 URL 必须是 HTTPS");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw failure("产物下载失败，HTTP " + response.statusCode());
            }
            try (InputStream input = response.body()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    if (output.size() + read > maxBytes) {
                        throw failure("产物超过允许的大小");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (IOException exception) {
            throw failure("产物下载失败：" + message(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("产物下载被中断");
        }
    }

    private BusinessException failure(String message) {
        return new BusinessException(
                AgentErrorCode.GENERATION_PROVIDER_ERROR, message);
    }

    private String message(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private static boolean allowHttpFromEnvironment() {
        return "true".equalsIgnoreCase(System.getenv("MEDIA_ALLOW_HTTP"));
    }
}
