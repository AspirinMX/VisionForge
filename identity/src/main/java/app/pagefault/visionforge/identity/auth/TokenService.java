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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.identity.config.IdentityProperties;
import app.pagefault.visionforge.identity.error.IdentityErrorCode;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserMapper;
import app.pagefault.visionforge.identity.user.UserRole;
import app.pagefault.visionforge.identity.user.UserStatus;
import app.pagefault.visionforge.identity.workspace.WorkspaceRole;
import app.pagefault.visionforge.identity.workspace.WorkspaceSummary;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class TokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final String SELECTION_SCOPE = "workspace:select";

    private final JwtEncoder jwtEncoder;
    private final IdentityProperties properties;
    private final JwtDecoder workspaceSelectionTokenDecoder;
    private final RefreshTokenMapper refreshTokenMapper;
    private final UserMapper userMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenPair issue(UserAccount user, WorkspaceSummary workspace) {
        Instant now = clock.instant();
        String rawRefreshToken = generateRefreshToken();
        createRefreshToken(user.getId(), rawRefreshToken, now);
        String accessToken = createAccessToken(user, workspace.id(), workspace.role(), now);
        return new TokenPair(
                accessToken,
                rawRefreshToken,
                "Bearer",
                properties.jwt().accessTokenTtl().toSeconds(),
                user.getId(),
                user.getEmail(),
                workspace.id(),
                workspace.role());
    }

    public AccessToken issueAccessToken(UserAccount user, WorkspaceSummary workspace) {
        Instant now = clock.instant();
        String accessToken = createAccessToken(user, workspace.id(), workspace.role(), now);
        return new AccessToken(
                accessToken,
                "Bearer",
                properties.jwt().accessTokenTtl().toSeconds(),
                user.getId(),
                user.getEmail(),
                workspace.id(),
                workspace.role());
    }

    public TokenPair issueWithRefresh(
            UserAccount user, WorkspaceSummary workspace, String rawRefreshToken) {
        Instant now = clock.instant();
        String accessToken = createAccessToken(user, workspace.id(), workspace.role(), now);
        return new TokenPair(
                accessToken,
                rawRefreshToken,
                "Bearer",
                properties.jwt().accessTokenTtl().toSeconds(),
                user.getId(),
                user.getEmail(),
                workspace.id(),
                workspace.role());
    }

    public String issueSelectionToken(UserAccount user) {
        Instant now = clock.instant();
        IdentityProperties.Jwt jwt = properties.jwt();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(user.getId().toString())
                .audience(List.of(jwt.selectionAudience()))
                .issuedAt(now)
                .expiresAt(now.plus(jwt.selectionTokenTtl()))
                .id(idGenerator.nextId().toString())
                .claim("scope", SELECTION_SCOPE)
                .build();
        return encode(claims);
    }

    public UUID parseSelectionToken(String rawSelectionToken) {
        if (rawSelectionToken == null || rawSelectionToken.isBlank()) {
            throw new BusinessException(IdentityErrorCode.TOKEN_INVALID);
        }
        try {
            Jwt jwt = workspaceSelectionTokenDecoder.decode(rawSelectionToken);
            if (!SELECTION_SCOPE.equals(jwt.getClaimAsString("scope"))) {
                throw new BusinessException(IdentityErrorCode.TOKEN_INVALID);
            }
            return UUID.fromString(jwt.getSubject());
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(IdentityErrorCode.TOKEN_INVALID);
        }
    }

    public RefreshSession authenticateRefresh(String rawRefreshToken) {
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
        return new RefreshSession(user, current);
    }

    public String rotateRefresh(RefreshSession session) {
        Instant now = clock.instant();
        String rawReplacement = generateRefreshToken();
        RefreshToken replacement = createRefreshToken(session.user().getId(), rawReplacement, now);
        RefreshToken current = session.token();
        current.setRevokedAt(now);
        current.setReplacedByTokenId(replacement.getId());
        refreshTokenMapper.updateById(current);
        return rawReplacement;
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

    private String createAccessToken(
            UserAccount user, UUID workspaceId, WorkspaceRole workspaceRole, Instant now) {
        IdentityProperties.Jwt jwt = properties.jwt();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .subject(user.getId().toString())
                .audience(List.of(jwt.audience()))
                .issuedAt(now)
                .expiresAt(now.plus(jwt.accessTokenTtl()))
                .id(idGenerator.nextId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("wsid", workspaceId.toString())
                .claim("platform_admin", user.getRole() == UserRole.ADMIN);
        if (workspaceRole != null) {
            claims.claim("ws_role", workspaceRole.name());
        }
        return encode(claims.build());
    }

    private String encode(JwtClaimsSet claims) {
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

    public record RefreshSession(UserAccount user, RefreshToken token) {
    }
}
