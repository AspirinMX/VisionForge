package app.pagefault.visionforge.identity.config;

import java.time.Clock;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.identity.auth.AuthService;
import app.pagefault.visionforge.identity.auth.RefreshTokenMapper;
import app.pagefault.visionforge.identity.auth.TokenService;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfiguration {

    private static final int MIN_SECRET_BYTES = 32;
    private static final int DEFAULT_BCRYPT_STRENGTH = 12;

    @Bean
    public PasswordEncoder passwordEncoder(IdentityProperties properties) {
        int strength = properties.password() == null
                ? DEFAULT_BCRYPT_STRENGTH
                : properties.password().bcryptStrength();
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    public JwtEncoder jwtEncoder(IdentityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    public JwtDecoder jwtDecoder(IdentityProperties properties) {
        IdentityProperties.Jwt jwt = requireJwt(properties);
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwt.issuer()),
                audienceValidator(jwt.audience())));
        return decoder;
    }

    @Bean
    public TokenService tokenService(
            JwtEncoder jwtEncoder,
            IdentityProperties properties,
            RefreshTokenMapper refreshTokenMapper,
            UserMapper userMapper,
            IdGenerator idGenerator,
            Clock clock) {
        return new TokenService(
                jwtEncoder,
                properties,
                refreshTokenMapper,
                userMapper,
                idGenerator,
                clock);
    }

    @Bean
    public IdentityService identityService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            IdGenerator idGenerator,
            Clock clock,
            ObjectProvider<DynamicRuntimeConfig> runtimeConfigProvider) {
        return new IdentityService(
                userMapper, passwordEncoder, idGenerator, clock, runtimeConfigProvider.getIfAvailable());
    }

    @Bean
    public AuthService authService(
            IdentityService identityService,
            TokenService tokenService) {
        return new AuthService(identityService, tokenService);
    }

    private OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Invalid audience", null));
    }

    private SecretKey secretKey(IdentityProperties properties) {
        IdentityProperties.Jwt jwt = requireJwt(properties);
        if (jwt.secret() == null || jwt.secret().isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(jwt.secret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT_SECRET must be valid Base64", exception);
        }
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT secret must be at least 256 bits");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private IdentityProperties.Jwt requireJwt(IdentityProperties properties) {
        IdentityProperties.Jwt jwt = properties.jwt();
        if (jwt == null
                || jwt.secret() == null
                || jwt.issuer() == null
                || jwt.audience() == null
                || jwt.accessTokenTtl() == null
                || jwt.refreshTokenTtl() == null) {
            throw new IllegalStateException("visionforge.security.jwt configuration is incomplete");
        }
        return jwt;
    }
}
