package app.pagefault.visionforge.api.security;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Configurable CORS policy for HTTP API clients.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "visionforge.security.cors")
public class WebCorsProperties {

    private boolean enabled = true;

    private List<String> allowedOrigins = List.of("http://localhost:3000", "http://127.0.0.1:3000");

    private List<String> allowedOriginPatterns = List.of();

    private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private List<String> allowedHeaders = List.of("*");

    private List<String> exposedHeaders = List.of(
            "X-Request-Id",
            "Retry-After",
            "X-RateLimit-Limit",
            "X-RateLimit-Remaining",
            "Content-Disposition");

    private boolean allowCredentials = true;

    private Duration maxAge = Duration.ofHours(1);
}
