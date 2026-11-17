package app.pagefault.visionforge.identity.user;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.identity.error.IdentityErrorCode;

import cn.hutool.core.lang.Validator;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class IdentityService {

    private static final int PASSWORD_MIN_LENGTH = 12;
    private static final int PASSWORD_MAX_LENGTH = 72;
    private static final int DISPLAY_NAME_MAX_LENGTH = 100;
    private static final long MAX_PAGE_SIZE = 200;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    @Transactional
    @BizLog(
            type = "IDENTITY_USER",
            subType = "REGISTER",
            bizNo = "#result.id",
            success = "用户「{{#result.displayName}}」注册成功",
            fail = "用户「{{#maskEmail(#email)}}」注册失败：{{#errorMessage}}")
    public UserAccount register(String email, String rawPassword, String displayName) {
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

    public PageResult<UserAccount> listUsers(long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<UserAccount> result = userMapper.selectPage(
                new Page<>(safePage, safeSize),
                Wrappers.<UserAccount>lambdaQuery().orderByAsc(UserAccount::getCreatedAt));
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
        String safeDisplayName = displayName == null ? "" : displayName.trim();
        if (safeDisplayName.isEmpty() || safeDisplayName.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "显示名称长度必须在 1 到 100 之间");
        }
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
}
