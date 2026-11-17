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
    @BizLog(
            type = "IDENTITY_AUTH",
            subType = "LOGIN",
            bizNo = "#result.userId",
            success = "用户「{{#maskEmail(#result.email)}}」登录成功",
            fail = "用户「{{#maskEmail(#email)}}」登录失败：{{#errorMessage}}")
    public TokenPair login(String email, String rawPassword) {
        UserAccount user = identityService.authenticate(email, rawPassword);
        return tokenService.issue(user.getId(), user.getEmail(), user.getRole());
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        return tokenService.refresh(rawRefreshToken);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        tokenService.revoke(rawRefreshToken);
    }
}
