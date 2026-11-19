package app.pagefault.visionforge.api.identity;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.identity.dto.AdminCreateUserRequest;
import app.pagefault.visionforge.api.identity.dto.AdminResetPasswordRequest;
import app.pagefault.visionforge.api.identity.dto.ChangeRoleRequest;
import app.pagefault.visionforge.api.identity.dto.ChangeUserStatusRequest;
import app.pagefault.visionforge.api.identity.dto.RevokeSessionsResponse;
import app.pagefault.visionforge.api.identity.dto.UserResponse;
import app.pagefault.visionforge.common.alert.AlertEvent;
import app.pagefault.visionforge.common.alert.AlertPublisher;
import app.pagefault.visionforge.common.alert.AlertSeverity;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.identity.auth.AuthService;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserRole;
import app.pagefault.visionforge.identity.user.UserStatus;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final IdentityService identityService;
    private final AuthService authService;
    private final ObjectProvider<AlertPublisher> alertPublisherProvider;

    @GetMapping
    public ApiResponse<PageResult<UserResponse>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UserStatus status) {
        PageResult<UserAccount> users = identityService.listUsers(page, size, q, role, status);
        PageResult<UserResponse> response = PageResult.of(
                users.items().stream().map(UserResponse::from).toList(),
                users.page(),
                users.size(),
                users.total());
        return ApiResponse.success(response);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> create(@Valid @RequestBody AdminCreateUserRequest request) {
        UserAccount created = identityService.createUserByAdmin(
                request.email(), request.password(), request.displayName(), request.role());
        return ApiResponse.success(UserResponse.from(created));
    }

    @PutMapping("/{id}/role")
    public ApiResponse<UserResponse> changeRole(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeRoleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = AuthenticatedUser.id(jwt);
        UserAccount user = identityService.changeRole(actorId, id, request.role());
        notifySecurityChange("管理员调整用户系统角色", "管理员 " + actorId + " 将用户 " + id + " 角色设置为 " + request.role(), id);
        return ApiResponse.success(UserResponse.from(user));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<UserResponse> changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeUserStatusRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = AuthenticatedUser.id(jwt);
        UserAccount user = authService.updateUserStatus(actorId, id, request.status());
        if (request.status() == UserStatus.DISABLED) {
            notifySecurityChange("管理员禁用用户账号", "管理员 " + actorId + " 已禁用用户 " + id + " 并吊销其活跃会话", id);
        }
        return ApiResponse.success(UserResponse.from(user));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody AdminResetPasswordRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        authService.resetUserPassword(
                AuthenticatedUser.id(jwt), id, request.newPassword());
        return ApiResponse.success();
    }

    @PostMapping("/{id}/revoke-sessions")
    public ApiResponse<RevokeSessionsResponse> revokeSessions(@PathVariable UUID id) {
        int revoked = authService.revokeUserSessions(id);
        return ApiResponse.success(new RevokeSessionsResponse(revoked));
    }

    private void notifySecurityChange(String title, String summary, UUID targetId) {
        AlertPublisher publisher = alertPublisherProvider.getIfAvailable();
        if (publisher != null) {
            publisher.publish(AlertEvent.of(
                    AlertEvent.EVENT_ADMIN_SECURITY_CHANGE,
                    AlertSeverity.INFO,
                    "visionforge-api",
                    title,
                    summary,
                    targetId.toString()));
        }
    }
}
