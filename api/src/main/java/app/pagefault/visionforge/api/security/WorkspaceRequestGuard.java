package app.pagefault.visionforge.api.security;

import java.time.Clock;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.common.audit.BizLogRecord;
import app.pagefault.visionforge.common.audit.BizLogStore;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.common.logging.TraceContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Enforces the workspace isolation rule for path-scoped requests and records a security audit
 * entry when a token is used against a different workspace.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceRequestGuard {

    private final BizLogStore bizLogStore;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public void require(Jwt jwt, UUID workspaceId) {
        UUID tokenWorkspaceId = AuthenticatedUser.workspaceId(jwt);
        if (tokenWorkspaceId == null || !tokenWorkspaceId.equals(workspaceId)) {
            recordDeniedAccess(jwt, tokenWorkspaceId, workspaceId);
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    private void recordDeniedAccess(Jwt jwt, UUID tokenWorkspaceId, UUID targetWorkspaceId) {
        BizLogRecord record = new BizLogRecord(
                idGenerator.nextId(),
                "IDENTITY_SECURITY",
                "WORKSPACE_ACCESS_DENIED",
                targetWorkspaceId == null ? null : targetWorkspaceId.toString(),
                AuthenticatedUser.id(jwt),
                jwt.getClaimAsString("email"),
                tokenWorkspaceId,
                false,
                "拒绝跨工作空间访问：token 工作空间=" + tokenWorkspaceId + "，目标工作空间=" + targetWorkspaceId,
                null,
                TraceContext.currentTraceId(),
                "workspace id in path does not match access token",
                clock.instant());
        try {
            bizLogStore.save(record);
        } catch (RuntimeException exception) {
            log.warn("Could not persist workspace access denial audit record", exception);
        }
    }
}
