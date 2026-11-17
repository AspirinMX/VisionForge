package app.pagefault.visionforge.identity.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.identity.error.IdentityErrorCode;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.workspace.WorkspaceActor;
import app.pagefault.visionforge.identity.workspace.WorkspaceService;
import app.pagefault.visionforge.identity.workspace.WorkspaceSummary;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AuthService {

    private final IdentityService identityService;
    private final TokenService tokenService;
    private final WorkspaceService workspaceService;

    @Transactional
    public UserAccount register(String email, String rawPassword, String displayName) {
        UserAccount user = identityService.register(email, rawPassword, displayName);
        workspaceService.createPersonalWorkspace(user.getId());
        return user;
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
        List<WorkspaceSummary> workspaces = workspaceService.listForUser(user.getId());
        if (workspaces.isEmpty()) {
            workspaceService.ensurePersonalWorkspace(user.getId());
            workspaces = workspaceService.listForUser(user.getId());
        }
        if (workspaces.isEmpty()) {
            throw new BusinessException(IdentityErrorCode.NO_WORKSPACE_AVAILABLE);
        }
        if (workspaces.size() == 1) {
            return LoginResult.selected(tokenService.issue(user, workspaces.get(0)), workspaces, user);
        }
        return LoginResult.selectionRequired(
                tokenService.issueSelectionToken(user), workspaces, user);
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_AUTH",
            subType = "WORKSPACE_SELECT",
            bizNo = "#result.workspaceId",
            success = "用户「{{#maskEmail(#result.email)}}」进入工作空间 {{#result.workspaceId}}",
            fail = "选择工作空间失败：{{#errorMessage}}")
    public TokenPair selectWorkspace(String selectionToken, UUID workspaceId) {
        UUID userId = tokenService.parseSelectionToken(selectionToken);
        UserAccount user = identityService.getActiveUser(userId);
        WorkspaceSummary workspace = workspaceService.getSummary(WorkspaceActor.of(user), workspaceId);
        return tokenService.issue(user, workspace);
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_AUTH",
            subType = "WORKSPACE_SWITCH",
            bizNo = "#result.workspaceId",
            success = "用户「{{#maskEmail(#result.email)}}」切换到工作空间 {{#result.workspaceId}}",
            fail = "切换工作空间失败：{{#errorMessage}}")
    public AccessToken switchWorkspace(UUID userId, UUID workspaceId) {
        UserAccount user = identityService.getActiveUser(userId);
        WorkspaceSummary workspace = workspaceService.getSummary(WorkspaceActor.of(user), workspaceId);
        return tokenService.issueAccessToken(user, workspace);
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken, UUID workspaceId) {
        TokenService.RefreshSession session = tokenService.authenticateRefresh(rawRefreshToken);
        UserAccount user = session.user();
        WorkspaceSummary workspace = workspaceService.getSummary(WorkspaceActor.of(user), workspaceId);
        String rawReplacement = tokenService.rotateRefresh(session);
        return tokenService.issueWithRefresh(user, workspace, rawReplacement);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        tokenService.revoke(rawRefreshToken);
    }
}
