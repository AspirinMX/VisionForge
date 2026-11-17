package app.pagefault.visionforge.api.identity;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.identity.dto.AccessTokenResponse;
import app.pagefault.visionforge.api.identity.dto.LoginRequest;
import app.pagefault.visionforge.api.identity.dto.LoginResponse;
import app.pagefault.visionforge.api.identity.dto.RefreshRequest;
import app.pagefault.visionforge.api.identity.dto.RegisterRequest;
import app.pagefault.visionforge.api.identity.dto.SelectWorkspaceRequest;
import app.pagefault.visionforge.api.identity.dto.SwitchWorkspaceRequest;
import app.pagefault.visionforge.api.identity.dto.TokenResponse;
import app.pagefault.visionforge.api.identity.dto.UserResponse;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.identity.auth.AccessToken;
import app.pagefault.visionforge.identity.auth.AuthService;
import app.pagefault.visionforge.identity.auth.LoginResult;
import app.pagefault.visionforge.identity.auth.TokenPair;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final IdentityService identityService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserAccount user = authService.register(
                request.email(), request.password(), request.displayName());
        return ApiResponse.success(UserResponse.from(user));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResult result = authService.login(request.email(), request.password());
        return ApiResponse.success(LoginResponse.from(result));
    }

    @PostMapping("/workspace")
    public ApiResponse<TokenResponse> selectWorkspace(
            @Valid @RequestBody SelectWorkspaceRequest request) {
        TokenPair pair = authService.selectWorkspace(
                request.selectionToken(), request.workspaceId());
        return ApiResponse.success(TokenResponse.from(pair));
    }

    @PostMapping("/switch-workspace")
    public ApiResponse<AccessTokenResponse> switchWorkspace(
            @Valid @RequestBody SwitchWorkspaceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        AccessToken token = authService.switchWorkspace(
                AuthenticatedUser.id(jwt), request.workspaceId());
        return ApiResponse.success(AccessTokenResponse.from(token));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenPair pair = authService.refresh(request.refreshToken(), request.workspaceId());
        return ApiResponse.success(TokenResponse.from(pair));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ApiResponse.success();
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = identityService.getActiveUser(AuthenticatedUser.id(jwt));
        return ApiResponse.success(UserResponse.from(user));
    }
}
