package app.pagefault.visionforge.identity.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "visionforge.security")
public record IdentityProperties(Jwt jwt, Password password) {

    public record Jwt(
            String secret,
            String issuer,
            String audience,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            Duration selectionTokenTtl) {

        public String selectionAudience() {
            return audience + "-workspace-select";
        }
    }

    public record Password(int bcryptStrength) {
    }
}
