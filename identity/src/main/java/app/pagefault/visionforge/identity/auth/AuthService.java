package app.pagefault.visionforge.identity.auth;

import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserStatus;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AuthService {

    private final IdentityService identityService;
    private final TokenService tokenService;

    @Transactional
    public UserAccount register(String email, String rawPassword, String displayName) {
        return identityService.register(email, rawPassword, displayName);
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_AUTH",
            subType = "LOGIN",
            bizNo = "#result.userId",
            success = "用户「{{#maskEmail(#result.email)}}」登录成功",
            fail = "用户「{{#maskEmail(#email)}}」登录失败：{{#errorMessage}}")
    public LoginResult login(String email, String rawPassword) {
        UserAccount user = identityService.authenticate(email, rawPassword);
        return LoginResult.of(tokenService.issue(user), user);
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        TokenService.RefreshSession session = tokenService.authenticateRefresh(rawRefreshToken);
        UserAccount user = session.user();
        String rawReplacement = tokenService.rotateRefresh(session);
        return tokenService.issueWithRefresh(user, rawReplacement);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        tokenService.revoke(rawRefreshToken);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        identityService.changePassword(userId, currentPassword, newPassword);
        tokenService.revokeAllForUser(userId);
    }

    @Transactional
    public UserAccount updateUserStatus(UUID actorId, UUID targetUserId, UserStatus status) {
        UserAccount updated = identityService.updateStatus(actorId, targetUserId, status);
        if (status == UserStatus.DISABLED) {
            tokenService.revokeAllForUser(targetUserId);
        }
        return updated;
    }

    @Transactional
    public void resetUserPassword(UUID actorId, UUID targetUserId, String newPassword) {
        identityService.adminResetPassword(actorId, targetUserId, newPassword);
        tokenService.revokeAllForUser(targetUserId);
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_USER",
            subType = "REVOKE_SESSIONS",
            bizNo = "#targetUserId",
            success = "管理员「{{#maskEmail(#operator.name)}}」强制下线用户 {{#targetUserId}}（吊销 {{#result}} 个会话）",
            fail = "强制下线失败：{{#errorMessage}}",
            sync = true)
    public int revokeUserSessions(UUID targetUserId) {
        identityService.getUser(targetUserId);
        return tokenService.revokeAllForUser(targetUserId);
    }
}
