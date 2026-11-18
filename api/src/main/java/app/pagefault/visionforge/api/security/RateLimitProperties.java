package app.pagefault.visionforge.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Configures Redis sliding-window rate limits for sensitive and costly API routes.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "visionforge.security.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    private boolean failOpen = true;

    private boolean trustForwardedHeaders = false;

    private int windowSeconds = 60;

    private int authPerMinute = 20;

    private int redeemPerMinute = 10;

    private int generationPerMinute = 30;

    private int uploadPerMinute = 30;
}
