package app.pagefault.visionforge.api.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import app.pagefault.visionforge.api.security.SecurityConfiguration;
import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.identity.auth.AuthService;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserRole;
import app.pagefault.visionforge.identity.user.UserStatus;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, AdminUserController.class})
@Import(SecurityConfiguration.class)
class IdentityControllersTest {

    private static final UUID ADMIN_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000001");
    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityService identityService;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void stubJwtTokens() {
        Instant now = Instant.parse("2026-11-19T00:00:00Z");
        Jwt adminJwt = Jwt.withTokenValue("admin-jwt")
                .header("alg", "HS256")
                .subject(ADMIN_ID.toString())
                .claim("role", "ADMIN")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
        Jwt userJwt = Jwt.withTokenValue("user-jwt")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .claim("role", "USER")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
        when(jwtDecoder.decode("admin-jwt")).thenReturn(adminJwt);
        when(jwtDecoder.decode("user-jwt")).thenReturn(userJwt);
    }

    @Test
    void regularUserCanUpdateProfileAndChangePasswordButCannotAccessAdminUsers() throws Exception {
        UserAccount updated = userAccount(USER_ID, "alice@example.com", "Alice New", UserRole.USER, UserStatus.ACTIVE);
        when(identityService.updateProfile(USER_ID, "Alice New")).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/auth/me")
                        .header("Authorization", "Bearer user-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alice New\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.displayName").value("Alice New"));

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer user-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"OldPassword123!\",\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        verify(authService).changePassword(USER_ID, "OldPassword123!", "NewPassword123!");

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
    }

    @Test
    void adminCanFilterCreateDisableResetPasswordAndRevokeSessions() throws Exception {
        UserAccount target = userAccount(USER_ID, "alice@example.com", "Alice", UserRole.USER, UserStatus.ACTIVE);
        when(identityService.listUsers(1L, 20L, "alice", UserRole.USER, UserStatus.ACTIVE))
                .thenReturn(PageResult.of(List.of(target), 1L, 20L, 1L));
        when(identityService.createUserByAdmin("bob@example.com", "BobPassword123!", "Bob", UserRole.USER))
                .thenReturn(userAccount(UUID.randomUUID(), "bob@example.com", "Bob", UserRole.USER, UserStatus.ACTIVE));

        UserAccount disabled = userAccount(USER_ID, "alice@example.com", "Alice", UserRole.USER, UserStatus.DISABLED);
        when(authService.updateUserStatus(ADMIN_ID, USER_ID, UserStatus.DISABLED)).thenReturn(disabled);
        when(authService.revokeUserSessions(USER_ID)).thenReturn(4);

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("q", "alice")
                        .param("role", "USER")
                        .param("status", "ACTIVE")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].email").value("alice@example.com"));

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"password\":\"BobPassword123!\",\"displayName\":\"Bob\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value("bob@example.com"));

        mockMvc.perform(put("/api/v1/admin/users/" + USER_ID + "/status")
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(post("/api/v1/admin/users/" + USER_ID + "/reset-password")
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"ResetPassword123!\"}"))
                .andExpect(status().isOk());
        verify(authService).resetUserPassword(eq(ADMIN_ID), eq(USER_ID), eq("ResetPassword123!"));

        mockMvc.perform(post("/api/v1/admin/users/" + USER_ID + "/revoke-sessions")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revokedCount").value(4));
    }

    private UserAccount userAccount(
            UUID id, String email, String displayName, UserRole role, UserStatus status) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus(status);
        user.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        return user;
    }
}
