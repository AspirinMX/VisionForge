package app.pagefault.visionforge.identity.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.identity.config.IdentityProperties;
import app.pagefault.visionforge.identity.user.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TokenServiceTest {

    @Test
    void revokeAllForUserUpdatesActiveRefreshTokens() {
        JwtEncoder jwtEncoder = mock(JwtEncoder.class);
        IdentityProperties props = new IdentityProperties(
                new IdentityProperties.Jwt("secret", "visionforge", "visionforge-api",
                        Duration.ofMinutes(15), Duration.ofDays(30)),
                new IdentityProperties.Password(12));
        RefreshTokenMapper refreshTokenMapper = mock(RefreshTokenMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        IdGenerator idGenerator = UUID::randomUUID;
        Instant now = Instant.parse("2026-11-19T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        TokenService tokenService = new TokenService(
                jwtEncoder, props, refreshTokenMapper, userMapper, idGenerator, clock);

        assertThat(tokenService.revokeAllForUser(null)).isZero();
        verifyNoInteractions(refreshTokenMapper);

        UUID userId = UUID.randomUUID();
        when(refreshTokenMapper.revokeAllActiveByUserId(userId, now)).thenReturn(3);

        int count = tokenService.revokeAllForUser(userId);
        assertThat(count).isEqualTo(3);
        verify(refreshTokenMapper).revokeAllActiveByUserId(userId, now);
    }
}
