package app.pagefault.visionforge.api.identity;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.identity.dto.ChangeRoleRequest;
import app.pagefault.visionforge.api.identity.dto.UserResponse;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final IdentityService identityService;

    @GetMapping
    public ApiResponse<PageResult<UserResponse>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        PageResult<UserAccount> users = identityService.listUsers(page, size);
        PageResult<UserResponse> response = PageResult.of(
                users.items().stream().map(UserResponse::from).toList(),
                users.page(),
                users.size(),
                users.total());
        return ApiResponse.success(response);
    }

    @PutMapping("/{id}/role")
    public ApiResponse<UserResponse> changeRole(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeRoleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UserAccount user = identityService.changeRole(
                AuthenticatedUser.id(jwt), id, request.role());
        return ApiResponse.success(UserResponse.from(user));
    }
}
