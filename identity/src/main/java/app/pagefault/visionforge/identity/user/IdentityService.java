package app.pagefault.visionforge.identity.user;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.identity.error.IdentityErrorCode;

import cn.hutool.core.lang.Validator;

public class IdentityService {

    private static final int PASSWORD_MIN_LENGTH = 12;
    private static final int PASSWORD_MAX_LENGTH = 72;
    private static final int DISPLAY_NAME_MAX_LENGTH = 100;
    private static final long MAX_PAGE_SIZE = 200;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final DynamicRuntimeConfig runtimeConfig;

    public IdentityService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            IdGenerator idGenerator,
            Clock clock) {
        this(userMapper, passwordEncoder, idGenerator, clock, null);
    }

    public IdentityService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            IdGenerator idGenerator,
            Clock clock,
            DynamicRuntimeConfig runtimeConfig) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.runtimeConfig = runtimeConfig;
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_USER",
            subType = "REGISTER",
            bizNo = "#result.id",
            success = "用户「{{#result.displayName}}」注册成功",
            fail = "用户「{{#maskEmail(#email)}}」注册失败：{{#errorMessage}}")
    public UserAccount register(String email, String rawPassword, String displayName) {
        if (runtimeConfig != null && !runtimeConfig.registrationEnabled(true)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "当前系统已关闭公开注册");
        }
        return createUser(email, rawPassword, displayName, UserRole.USER);
    }

    public UserAccount authenticate(String email, String rawPassword) {
        UserAccount user = findByEmail(normalizeEmail(email));
        if (user == null
                || user.getStatus() != UserStatus.ACTIVE
                || user.getPasswordHash() == null
                || rawPassword == null
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(IdentityErrorCode.INVALID_CREDENTIALS);
        }
        return user;
    }

    public UserAccount getActiveUser(UUID userId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(IdentityErrorCode.TOKEN_INVALID);
        }
        return user;
    }

    public UserAccount getUser(UUID userId) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(IdentityErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_USER",
            subType = "ADMIN_CREATE",
            bizNo = "#result.id",
            success = "管理员创建用户「{{#result.displayName}}」({{#maskEmail(#result.email)}}，角色={{#result.role}})",
            fail = "管理员创建用户「{{#maskEmail(#email)}}」失败：{{#errorMessage}}",
            sync = true)
    public UserAccount createUserByAdmin(String email, String rawPassword, String displayName, UserRole role) {
        return createUser(email, rawPassword, displayName, role == null ? UserRole.USER : role);
    }

    public PageResult<UserAccount> listUsers(long page, long size) {
        return listUsers(page, size, null, null, null);
    }

    public PageResult<UserAccount> listUsers(
            long page, long size, String query, UserRole role, UserStatus status) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<UserAccount> wrapper = Wrappers.<UserAccount>lambdaQuery()
                .orderByAsc(UserAccount::getCreatedAt);
        if (query != null && !query.isBlank()) {
            String lowered = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            wrapper.and(q -> q
                    .apply("LOWER(email) LIKE {0}", lowered)
                    .or()
                    .apply("LOWER(display_name) LIKE {0}", lowered));
        }
        if (role != null) {
            wrapper.eq(UserAccount::getRole, role);
        }
        if (status != null) {
            wrapper.eq(UserAccount::getStatus, status);
        }
        Page<UserAccount> result = userMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result.getRecords(), result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_USER",
            subType = "ROLE_CHANGE",
            bizNo = "#targetUserId",
            success = "管理员「{{#maskEmail(#operator.name)}}」将用户 {{#targetUserId}} 的角色改为 {{#newRole}}",
            fail = "角色修改失败：{{#errorMessage}}",
            sync = true)
    public UserAccount changeRole(UUID actorId, UUID targetUserId, UserRole newRole) {
        if (actorId == null || targetUserId == null || newRole == null) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        if (actorId.equals(targetUserId)) {
            throw new BusinessException(IdentityErrorCode.SELF_ROLE_CHANGE_FORBIDDEN);
        }
        UserAccount target = getUser(targetUserId);
        target.setRole(newRole);
        target.setUpdatedAt(clock.instant());
        userMapper.updateById(target);
        return target;
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_USER",
            subType = "STATUS_CHANGE",
            bizNo = "#targetUserId",
            success = "管理员「{{#maskEmail(#operator.name)}}」将用户 {{#targetUserId}} 状态变更为 {{#status}}",
            fail = "用户状态变更失败：{{#errorMessage}}",
            sync = true)
    public UserAccount updateStatus(UUID actorId, UUID targetUserId, UserStatus status) {
        if (actorId == null || targetUserId == null || status == null) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        if (actorId.equals(targetUserId) && status == UserStatus.DISABLED) {
            throw new BusinessException(IdentityErrorCode.SELF_STATUS_CHANGE_FORBIDDEN);
        }
        UserAccount target = getUser(targetUserId);
        target.setStatus(status);
        target.setUpdatedAt(clock.instant());
        userMapper.updateById(target);
        return target;
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_USER",
            subType = "ADMIN_RESET_PASSWORD",
            bizNo = "#targetUserId",
            success = "管理员「{{#maskEmail(#operator.name)}}」重置了用户 {{#targetUserId}} 的登录密码",
            fail = "管理员重置密码失败：{{#errorMessage}}",
            sync = true)
    public UserAccount adminResetPassword(UUID actorId, UUID targetUserId, String newPassword) {
        if (actorId == null || targetUserId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        validatePassword(newPassword);
        UserAccount target = getUser(targetUserId);
        target.setPasswordHash(passwordEncoder.encode(newPassword));
        target.setUpdatedAt(clock.instant());
        userMapper.updateById(target);
        return target;
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_USER",
            subType = "PROFILE_UPDATE",
            bizNo = "#userId",
            success = "用户更新显示名称为「{{#result.displayName}}」",
            fail = "更新个人资料失败：{{#errorMessage}}")
    public UserAccount updateProfile(UUID userId, String displayName) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        String safeDisplayName = validateDisplayName(displayName);
        UserAccount target = getActiveUser(userId);
        target.setDisplayName(safeDisplayName);
        target.setUpdatedAt(clock.instant());
        userMapper.updateById(target);
        return target;
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_USER",
            subType = "PASSWORD_CHANGE",
            bizNo = "#userId",
            success = "用户主动修改了登录密码",
            fail = "用户修改密码失败：{{#errorMessage}}")
    public UserAccount changePassword(UUID userId, String currentPassword, String newPassword) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        UserAccount target = getActiveUser(userId);
        if (currentPassword == null
                || target.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, target.getPasswordHash())) {
            throw new BusinessException(IdentityErrorCode.INVALID_CREDENTIALS, "当前密码不正确");
        }
        validatePassword(newPassword);
        target.setPasswordHash(passwordEncoder.encode(newPassword));
        target.setUpdatedAt(clock.instant());
        userMapper.updateById(target);
        return target;
    }

    @Transactional
    public UserAccount ensureAdmin(String email, String rawPassword, String displayName) {
        String normalized = normalizeEmail(email);
        validateEmail(normalized);
        UserAccount existing = findByEmail(normalized);
        if (existing == null) {
            return createUser(email, rawPassword, displayName, UserRole.ADMIN);
        }
        if (existing.getRole() != UserRole.ADMIN) {
            existing.setRole(UserRole.ADMIN);
            existing.setUpdatedAt(clock.instant());
            userMapper.updateById(existing);
        }
        return existing;
    }

    private UserAccount createUser(String email, String rawPassword, String displayName, UserRole role) {
        String normalized = normalizeEmail(email);
        validateEmail(normalized);
        validatePassword(rawPassword);
        String safeDisplayName = validateDisplayName(displayName);
        if (findByEmail(normalized) != null) {
            throw new BusinessException(IdentityErrorCode.EMAIL_ALREADY_REGISTERED);
        }

        Instant now = clock.instant();
        UserAccount user = new UserAccount();
        user.setId(idGenerator.nextId());
        user.setEmail(normalized);
        user.setDisplayName(safeDisplayName);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus(UserStatus.ACTIVE);
        user.setRole(role);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(IdentityErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        return user;
    }

    private UserAccount findByEmail(String email) {
        return userMapper.selectOne(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getEmail, email));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private void validateEmail(String email) {
        if (email.isEmpty() || !Validator.isEmail(email)) {
            throw new BusinessException(IdentityErrorCode.INVALID_EMAIL);
        }
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null
                || rawPassword.length() < PASSWORD_MIN_LENGTH
                || rawPassword.length() > PASSWORD_MAX_LENGTH) {
            throw new BusinessException(IdentityErrorCode.WEAK_PASSWORD);
        }
    }

    private String validateDisplayName(String displayName) {
        String safeDisplayName = displayName == null ? "" : displayName.trim();
        if (safeDisplayName.isEmpty() || safeDisplayName.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "显示名称长度必须在 1 到 100 之间");
        }
        return safeDisplayName;
    }
}
