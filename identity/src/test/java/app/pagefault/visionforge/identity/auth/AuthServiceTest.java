package app.pagefault.visionforge.identity.auth;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private IdentityService identityService;
    private TokenService tokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        identityService = mock(IdentityService.class);
        tokenService = mock(TokenService.class);
        authService = new AuthService(identityService, tokenService);
    }

    @Test
    void changePasswordRevokesAllUserTokens() {
        UUID userId = UUID.randomUUID();

        authService.changePassword(userId, "OldPassword123!", "NewPassword123!");

        verify(identityService).changePassword(userId, "OldPassword123!", "NewPassword123!");
        verify(tokenService).revokeAllForUser(userId);
    }

    @Test
    void updateUserStatusRevokesTokensOnlyWhenDisabling() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UserAccount disabled = new UserAccount();
        disabled.setId(targetId);
        disabled.setStatus(UserStatus.DISABLED);
        UserAccount enabled = new UserAccount();
        enabled.setId(targetId);
        enabled.setStatus(UserStatus.ACTIVE);

        when(identityService.updateStatus(actorId, targetId, UserStatus.DISABLED)).thenReturn(disabled);
        when(identityService.updateStatus(actorId, targetId, UserStatus.ACTIVE)).thenReturn(enabled);

        assertThat(authService.updateUserStatus(actorId, targetId, UserStatus.ACTIVE).getStatus())
                .isEqualTo(UserStatus.ACTIVE);
        verify(tokenService, never()).revokeAllForUser(targetId);

        assertThat(authService.updateUserStatus(actorId, targetId, UserStatus.DISABLED).getStatus())
                .isEqualTo(UserStatus.DISABLED);
        verify(tokenService).revokeAllForUser(targetId);
    }

    @Test
    void resetUserPasswordAndRevokeSessionsRevokeAllUserTokens() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(tokenService.revokeAllForUser(targetId)).thenReturn(2);

        authService.resetUserPassword(actorId, targetId, "ResetPassword123!");
        verify(identityService).adminResetPassword(actorId, targetId, "ResetPassword123!");
        verify(tokenService).revokeAllForUser(targetId);

        int count = authService.revokeUserSessions(targetId);
        assertThat(count).isEqualTo(2);
        verify(identityService).getUser(targetId);
    }
}
