package app.pagefault.visionforge.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.identity.error.IdentityErrorCode;

class IdentityServiceTest {

    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private IdentityService identityService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        IdGenerator idGenerator = () -> USER_ID;
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        identityService = new IdentityService(userMapper, passwordEncoder, idGenerator, clock);
    }

    @Test
    void registerNormalizesEmailAndCreatesRegularUser() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode("UserPassword123!")).thenReturn("bcrypt-hash");

        UserAccount user = identityService.register(
                " Alice@Example.com ", "UserPassword123!", "Alice");

        assertThat(user.getId()).isEqualTo(USER_ID);
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getDisplayName()).isEqualTo("Alice");
        assertThat(user.getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        verify(userMapper).insert(user);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        UserAccount existing = new UserAccount();
        existing.setId(UUID.randomUUID());
        existing.setEmail("alice@example.com");
        when(userMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> identityService.register(
                "alice@example.com", "UserPassword123!", "Alice"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(IdentityErrorCode.EMAIL_ALREADY_REGISTERED));
    }

    @Test
    void authenticateRejectsWrongPassword() {
        UserAccount existing = activeUser();
        existing.setPasswordHash("bcrypt-hash");
        when(userMapper.selectOne(any())).thenReturn(existing);
        when(passwordEncoder.matches("wrong-password", "bcrypt-hash")).thenReturn(false);

        assertThatThrownBy(() -> identityService.authenticate("alice@example.com", "wrong-password"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(IdentityErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void changeRoleRejectsSelfModification() {
        assertThatThrownBy(() -> identityService.changeRole(USER_ID, USER_ID, UserRole.ADMIN))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(IdentityErrorCode.SELF_ROLE_CHANGE_FORBIDDEN));
    }

    @Test
    void getActiveUserRejectsDisabledAccount() {
        UserAccount existing = activeUser();
        existing.setStatus(UserStatus.DISABLED);
        when(userMapper.selectById(USER_ID)).thenReturn(existing);

        assertThatThrownBy(() -> identityService.getActiveUser(USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(IdentityErrorCode.TOKEN_INVALID));
    }

    @Test
    void createUserByAdminCreatesSpecifiedRole() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode("AdminPassword123!")).thenReturn("encoded-admin");

        UserAccount admin = identityService.createUserByAdmin(
                "Ops@Example.com", "AdminPassword123!", "Ops Admin", UserRole.ADMIN);

        assertThat(admin.getEmail()).isEqualTo("ops@example.com");
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userMapper).insert(admin);
    }

    @Test
    void updateStatusRejectsSelfDisableAndAllowsEnablingOtherUser() {
        assertThatThrownBy(() -> identityService.updateStatus(USER_ID, USER_ID, UserStatus.DISABLED))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(IdentityErrorCode.SELF_STATUS_CHANGE_FORBIDDEN));

        UUID actorId = UUID.randomUUID();
        UserAccount target = activeUser();
        target.setStatus(UserStatus.DISABLED);
        when(userMapper.selectById(USER_ID)).thenReturn(target);

        UserAccount updated = identityService.updateStatus(actorId, USER_ID, UserStatus.ACTIVE);
        assertThat(updated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userMapper).updateById(target);
    }

    @Test
    void updateProfileAndChangePasswordValidateInputsAndEncodePassword() {
        UserAccount existing = activeUser();
        existing.setPasswordHash("old-hash");
        when(userMapper.selectById(USER_ID)).thenReturn(existing);
        when(passwordEncoder.matches("OldPassword123!", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("WrongPassword1!", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewPassword123!")).thenReturn("new-hash");

        UserAccount renamed = identityService.updateProfile(USER_ID, "  Alice Ren  ");
        assertThat(renamed.getDisplayName()).isEqualTo("Alice Ren");

        assertThatThrownBy(() -> identityService.changePassword(USER_ID, "WrongPassword1!", "NewPassword123!"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(IdentityErrorCode.INVALID_CREDENTIALS));

        UserAccount changed = identityService.changePassword(USER_ID, "OldPassword123!", "NewPassword123!");
        assertThat(changed.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void adminResetPasswordValidatesStrengthAndEncodes() {
        UUID actorId = UUID.randomUUID();
        UserAccount existing = activeUser();
        when(userMapper.selectById(USER_ID)).thenReturn(existing);
        when(passwordEncoder.encode("ResetPassword123!")).thenReturn("reset-hash");

        assertThatThrownBy(() -> identityService.adminResetPassword(actorId, USER_ID, "short"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(IdentityErrorCode.WEAK_PASSWORD));

        UserAccount reset = identityService.adminResetPassword(actorId, USER_ID, "ResetPassword123!");
        assertThat(reset.getPasswordHash()).isEqualTo("reset-hash");
        verify(userMapper).updateById(existing);
    }

    private UserAccount activeUser() {
        UserAccount user = new UserAccount();
        user.setId(USER_ID);
        user.setEmail("alice@example.com");
        user.setDisplayName("Alice");
        user.setStatus(UserStatus.ACTIVE);
        user.setRole(UserRole.USER);
        return user;
    }
}
