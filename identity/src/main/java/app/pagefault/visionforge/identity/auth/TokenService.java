package app.pagefault.visionforge.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.identity.config.IdentityProperties;
import app.pagefault.visionforge.identity.error.IdentityErrorCode;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserMapper;
import app.pagefault.visionforge.identity.user.UserRole;
import app.pagefault.visionforge.identity.user.UserStatus;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class TokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final JwtEncoder jwtEncoder;
    private final IdentityProperties properties;
    private final RefreshTokenMapper refreshTokenMapper;
    private final UserMapper userMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenPair issue(UUID userId, String email, UserRole role) {
        Instant now = clock.instant();
        String rawRefreshToken = generateRefreshToken();
        createRefreshToken(userId, rawRefreshToken, now);
        String accessToken = createAccessToken(userId, email, role, now);
        return new TokenPair(
                accessToken,
                rawRefreshToken,
                "Bearer",
                properties.jwt().accessTokenTtl().toSeconds(),
                userId,
                email);
    }

    public TokenPair refresh(String rawRefreshToken) {
        RefreshToken current = findByHash(hash(rawRefreshToken));
        Instant now = clock.instant();
        if (current == null
                || current.getRevokedAt() != null
                || !current.getExpiresAt().isAfter(now)) {
            throw new BusinessException(IdentityErrorCode.TOKEN_INVALID);
        }

        UserAccount user = userMapper.selectById(current.getUserId());
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(IdentityErrorCode.TOKEN_INVALID);
        }

        String rawReplacement = generateRefreshToken();
        RefreshToken replacement = createRefreshToken(user.getId(), rawReplacement, now);
        current.setRevokedAt(now);
        current.setReplacedByTokenId(replacement.getId());
        refreshTokenMapper.updateById(current);

        String accessToken = createAccessToken(user.getId(), user.getEmail(), user.getRole(), now);
        return new TokenPair(
                accessToken,
                rawReplacement,
                "Bearer",
                properties.jwt().accessTokenTtl().toSeconds(),
                user.getId(),
                user.getEmail());
    }

    public void revoke(String rawRefreshToken) {
        RefreshToken token = findByHash(hash(rawRefreshToken));
        if (token == null || token.getRevokedAt() != null) {
            return;
        }
        token.setRevokedAt(clock.instant());
        refreshTokenMapper.updateById(token);
    }

    private RefreshToken createRefreshToken(UUID userId, String rawToken, Instant now) {
        RefreshToken token = new RefreshToken();
        token.setId(idGenerator.nextId());
        token.setUserId(userId);
        token.setTokenHash(hash(rawToken));
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(properties.jwt().refreshTokenTtl()));
        refreshTokenMapper.insert(token);
        return token;
    }

    private RefreshToken findByHash(String tokenHash) {
        return refreshTokenMapper.selectOne(
                Wrappers.<RefreshToken>lambdaQuery().eq(RefreshToken::getTokenHash, tokenHash));
    }

    private String createAccessToken(UUID userId, String email, UserRole role, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(userId.toString())
                .audience(List.of(properties.jwt().audience()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.jwt().accessTokenTtl()))
                .id(idGenerator.nextId().toString())
                .claim("email", email)
                .claim("role", role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
