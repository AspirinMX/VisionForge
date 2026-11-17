package app.pagefault.visionforge.identity.auth;

import org.springframework.transaction.annotation.Transactional;

import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;

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
}
