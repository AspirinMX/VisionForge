package app.pagefault.visionforge.identity.workspace;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.identity.error.IdentityErrorCode;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserMapper;
import app.pagefault.visionforge.identity.user.UserStatus;

import cn.hutool.core.lang.Validator;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WorkspaceService {

    private static final int NAME_MAX_LENGTH = 100;
    private static final int DESCRIPTION_MAX_LENGTH = 500;
    private static final long MAX_PAGE_SIZE = 200;
    private static final String PERSONAL_WORKSPACE_NAME = "个人空间";

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final UserMapper userMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;

    @Transactional
    @BizLog(
            type = "IDENTITY_WORKSPACE",
            subType = "CREATE",
            bizNo = "#result.id",
            success = "工作空间「{{#result.name}}」创建成功",
            fail = "工作空间创建失败：{{#errorMessage}}")
    public Workspace create(WorkspaceActor actor, String name, String description) {
        requireActor(actor);
        Workspace workspace = newWorkspace(name, description, false);
        workspaceMapper.insert(workspace);
        createMembership(workspace.getId(), actor.userId(), WorkspaceRole.OWNER);
        return workspace;
    }

    @Transactional
    public Workspace createPersonalWorkspace(UUID userId) {
        Workspace workspace = newWorkspace(PERSONAL_WORKSPACE_NAME, null, true);
        workspaceMapper.insert(workspace);
        createMembership(workspace.getId(), userId, WorkspaceRole.OWNER);
        return workspace;
    }

    /**
     * Creates the personal workspace for accounts that predate workspace support. Registration and
     * bootstrap creation call this path so login never has to deal with an empty workspace list.
     */
    @Transactional
    public Workspace ensurePersonalWorkspace(UUID userId) {
        List<WorkspaceSummary> existing = listForUser(userId);
        if (!existing.isEmpty()) {
            return workspaceMapper.selectById(existing.get(0).id());
        }
        return createPersonalWorkspace(userId);
    }

    public List<WorkspaceSummary> listForUser(UUID userId) {
        List<WorkspaceMember> members = workspaceMemberMapper.selectList(
                Wrappers.<WorkspaceMember>lambdaQuery()
                        .eq(WorkspaceMember::getUserId, userId)
                        .eq(WorkspaceMember::getStatus, WorkspaceMemberStatus.ACTIVE)
                        .orderByAsc(WorkspaceMember::getJoinedAt));
        if (members.isEmpty()) {
            return List.of();
        }
        Map<UUID, Workspace> workspaces = workspaceMapper
                .selectBatchIds(members.stream().map(WorkspaceMember::getWorkspaceId).toList())
                .stream()
                .collect(Collectors.toMap(Workspace::getId, Function.identity()));
        return members.stream()
                .map(member -> {
                    Workspace workspace = workspaces.get(member.getWorkspaceId());
                    if (workspace == null || workspace.getStatus() != WorkspaceStatus.ACTIVE) {
                        return null;
                    }
                    return new WorkspaceSummary(
                            workspace.getId(), workspace.getName(), workspace.isPersonal(), member.getRole());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public PageResult<Workspace> listAll(long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<Workspace> result = workspaceMapper.selectPage(
                new Page<>(safePage, safeSize),
                Wrappers.<Workspace>lambdaQuery().orderByDesc(Workspace::getCreatedAt));
        return PageResult.of(result.getRecords(), result.getCurrent(), result.getSize(), result.getTotal());
    }

    public Workspace get(WorkspaceActor actor, UUID workspaceId) {
        return requireAccess(actor, workspaceId, WorkspaceRole.MEMBER);
    }

    public Workspace getForAdmin(UUID workspaceId) {
        Workspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(IdentityErrorCode.WORKSPACE_NOT_FOUND);
        }
        return workspace;
    }

    public WorkspaceSummary getSummary(WorkspaceActor actor, UUID workspaceId) {
        Workspace workspace = requireAccess(actor, workspaceId, WorkspaceRole.MEMBER);
        WorkspaceMember member = findMember(workspaceId, actor.userId());
        WorkspaceRole role = member == null ? null : member.getRole();
        return new WorkspaceSummary(workspace.getId(), workspace.getName(), workspace.isPersonal(), role);
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_WORKSPACE",
            subType = "UPDATE",
            bizNo = "#workspaceId",
            success = "工作空间 {{#workspaceId}} 已更新",
            fail = "工作空间更新失败：{{#errorMessage}}",
            sync = true)
    public Workspace update(WorkspaceActor actor, UUID workspaceId, String name, String description) {
        Workspace workspace = requireAccess(actor, workspaceId, WorkspaceRole.ADMIN);
        if (name != null) {
            workspace.setName(validateName(name));
        }
        if (description != null) {
            workspace.setDescription(validateDescription(description));
        }
        workspace.setUpdatedAt(clock.instant());
        workspaceMapper.updateById(workspace);
        return workspace;
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_WORKSPACE",
            subType = "ARCHIVE",
            bizNo = "#workspaceId",
            success = "工作空间 {{#workspaceId}} 已归档",
            fail = "工作空间归档失败：{{#errorMessage}}",
            sync = true)
    public void archive(WorkspaceActor actor, UUID workspaceId) {
        Workspace workspace = requireAccess(actor, workspaceId, WorkspaceRole.OWNER);
        workspace.setStatus(WorkspaceStatus.ARCHIVED);
        workspace.setUpdatedAt(clock.instant());
        workspaceMapper.updateById(workspace);
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_WORKSPACE",
            subType = "TRANSFER",
            bizNo = "#workspaceId",
            success = "工作空间 {{#workspaceId}} 所有权已转让给 {{#targetUserId}}",
            fail = "所有权转让失败：{{#errorMessage}}",
            sync = true)
    public WorkspaceMemberView transferOwnership(
            WorkspaceActor actor, UUID workspaceId, UUID targetUserId) {
        requireAccess(actor, workspaceId, WorkspaceRole.OWNER);
        WorkspaceMember target = requireMember(workspaceId, targetUserId);
        if (target.getRole() == WorkspaceRole.OWNER) {
            return toView(target);
        }

        WorkspaceMember currentOwner = workspaceMemberMapper.selectOne(
                Wrappers.<WorkspaceMember>lambdaQuery()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getRole, WorkspaceRole.OWNER));
        if (currentOwner == null) {
            throw new BusinessException(IdentityErrorCode.WORKSPACE_OWNER_REQUIRED);
        }

        Instant now = clock.instant();
        currentOwner.setRole(WorkspaceRole.ADMIN);
        currentOwner.setUpdatedAt(now);
        workspaceMemberMapper.updateById(currentOwner);

        target.setRole(WorkspaceRole.OWNER);
        target.setUpdatedAt(now);
        workspaceMemberMapper.updateById(target);
        return toView(target);
    }

    public List<WorkspaceMemberView> listMembers(WorkspaceActor actor, UUID workspaceId) {
        requireAccess(actor, workspaceId, WorkspaceRole.MEMBER);
        List<WorkspaceMember> members = workspaceMemberMapper.selectList(
                Wrappers.<WorkspaceMember>lambdaQuery()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .orderByAsc(WorkspaceMember::getJoinedAt));
        if (members.isEmpty()) {
            return List.of();
        }
        Map<UUID, UserAccount> users = userMapper
                .selectBatchIds(members.stream().map(WorkspaceMember::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        return members.stream().map(member -> toView(member, users.get(member.getUserId()))).toList();
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_WORKSPACE",
            subType = "MEMBER_ADD",
            bizNo = "#workspaceId",
            success = "成员「{{#maskEmail(#email)}}」已加入工作空间 {{#workspaceId}}",
            fail = "添加成员失败：{{#errorMessage}}",
            sync = true)
    public WorkspaceMemberView addMember(
            WorkspaceActor actor, UUID workspaceId, String email, WorkspaceRole role) {
        WorkspaceMember actorMember = requireManager(actor, workspaceId);
        if (role == null || role == WorkspaceRole.OWNER) {
            throw new BusinessException(IdentityErrorCode.INVALID_WORKSPACE_ROLE);
        }
        if (actorMember != null
                && actorMember.getRole() == WorkspaceRole.ADMIN
                && role == WorkspaceRole.ADMIN) {
            throw new BusinessException(IdentityErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        String normalized = normalizeEmail(email);
        if (normalized.isEmpty() || !Validator.isEmail(normalized)) {
            throw new BusinessException(IdentityErrorCode.INVALID_EMAIL);
        }
        UserAccount user = userMapper.selectOne(
                Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getEmail, normalized));
        if (user == null) {
            throw new BusinessException(IdentityErrorCode.USER_NOT_FOUND);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(IdentityErrorCode.ACCOUNT_DISABLED);
        }
        if (findMember(workspaceId, user.getId()) != null) {
            throw new BusinessException(IdentityErrorCode.MEMBER_ALREADY_EXISTS);
        }
        WorkspaceMember member = createMembership(workspaceId, user.getId(), role);
        return toView(member, user);
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_WORKSPACE",
            subType = "MEMBER_ROLE_CHANGE",
            bizNo = "#workspaceId",
            success = "成员 {{#targetUserId}} 的角色已改为 {{#newRole}}",
            fail = "修改成员角色失败：{{#errorMessage}}",
            sync = true)
    public WorkspaceMemberView changeMemberRole(
            WorkspaceActor actor, UUID workspaceId, UUID targetUserId, WorkspaceRole newRole) {
        WorkspaceMember actorMember = requireManager(actor, workspaceId);
        if (newRole == null || newRole == WorkspaceRole.OWNER) {
            throw new BusinessException(IdentityErrorCode.INVALID_WORKSPACE_ROLE);
        }
        WorkspaceMember target = requireMember(workspaceId, targetUserId);
        if (target.getRole() == WorkspaceRole.OWNER) {
            throw new BusinessException(IdentityErrorCode.CANNOT_CHANGE_OWNER_ROLE);
        }
        if (actorMember != null
                && actorMember.getRole() == WorkspaceRole.ADMIN
                && (target.getRole() == WorkspaceRole.ADMIN || newRole == WorkspaceRole.ADMIN)) {
            throw new BusinessException(IdentityErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        target.setRole(newRole);
        target.setUpdatedAt(clock.instant());
        workspaceMemberMapper.updateById(target);
        return toView(target);
    }

    @Transactional
    @BizLog(
            type = "IDENTITY_WORKSPACE",
            subType = "MEMBER_REMOVE",
            bizNo = "#workspaceId",
            success = "成员 {{#targetUserId}} 已移出工作空间 {{#workspaceId}}",
            fail = "移除成员失败：{{#errorMessage}}",
            sync = true)
    public void removeMember(WorkspaceActor actor, UUID workspaceId, UUID targetUserId) {
        WorkspaceMember actorMember = requireManager(actor, workspaceId);
        WorkspaceMember target = requireMember(workspaceId, targetUserId);
        if (target.getRole() == WorkspaceRole.OWNER) {
            throw new BusinessException(IdentityErrorCode.CANNOT_REMOVE_OWNER);
        }
        if (actorMember != null
                && actorMember.getRole() == WorkspaceRole.ADMIN
                && target.getRole() == WorkspaceRole.ADMIN) {
            throw new BusinessException(IdentityErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        workspaceMemberMapper.deleteById(target.getId());
    }

    private Workspace requireAccess(
            WorkspaceActor actor, UUID workspaceId, WorkspaceRole minimumRole) {
        requireActor(actor);
        if (workspaceId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT);
        }
        Workspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(IdentityErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (workspace.getStatus() != WorkspaceStatus.ACTIVE) {
            throw new BusinessException(IdentityErrorCode.WORKSPACE_ARCHIVED);
        }
        if (actor.platformAdmin()) {
            return workspace;
        }
        WorkspaceMember member = findMember(workspaceId, actor.userId());
        if (member == null || member.getStatus() != WorkspaceMemberStatus.ACTIVE) {
            throw new BusinessException(IdentityErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        if (!member.getRole().atLeast(minimumRole)) {
            throw new BusinessException(minimumRole == WorkspaceRole.OWNER
                    ? IdentityErrorCode.WORKSPACE_OWNER_REQUIRED
                    : IdentityErrorCode.WORKSPACE_ADMIN_REQUIRED);
        }
        return workspace;
    }

    private WorkspaceMember requireManager(WorkspaceActor actor, UUID workspaceId) {
        requireAccess(actor, workspaceId, WorkspaceRole.ADMIN);
        return actor.platformAdmin() ? null : findMember(workspaceId, actor.userId());
    }

    private WorkspaceMember requireMember(UUID workspaceId, UUID userId) {
        WorkspaceMember member = findMember(workspaceId, userId);
        if (member == null || member.getStatus() != WorkspaceMemberStatus.ACTIVE) {
            throw new BusinessException(IdentityErrorCode.MEMBER_NOT_FOUND);
        }
        return member;
    }

    private WorkspaceMember findMember(UUID workspaceId, UUID userId) {
        if (workspaceId == null || userId == null) {
            return null;
        }
        return workspaceMemberMapper.selectOne(
                Wrappers.<WorkspaceMember>lambdaQuery()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId));
    }

    private Workspace newWorkspace(String name, String description, boolean personal) {
        Instant now = clock.instant();
        Workspace workspace = new Workspace();
        workspace.setId(idGenerator.nextId());
        workspace.setName(validateName(name));
        workspace.setDescription(validateDescription(description));
        workspace.setStatus(WorkspaceStatus.ACTIVE);
        workspace.setPersonal(personal);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        return workspace;
    }

    private WorkspaceMember createMembership(UUID workspaceId, UUID userId, WorkspaceRole role) {
        Instant now = clock.instant();
        WorkspaceMember member = new WorkspaceMember();
        member.setId(idGenerator.nextId());
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(WorkspaceMemberStatus.ACTIVE);
        member.setJoinedAt(now);
        member.setUpdatedAt(now);
        workspaceMemberMapper.insert(member);
        return member;
    }

    private WorkspaceMemberView toView(WorkspaceMember member) {
        UserAccount user = userMapper.selectById(member.getUserId());
        return toView(member, user);
    }

    private WorkspaceMemberView toView(WorkspaceMember member, UserAccount user) {
        return new WorkspaceMemberView(
                member.getUserId(),
                user == null ? null : user.getEmail(),
                user == null ? null : user.getDisplayName(),
                member.getRole(),
                member.getJoinedAt());
    }

    private void requireActor(WorkspaceActor actor) {
        if (actor == null || actor.userId() == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    private String validateName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "工作空间名称长度必须在 1 到 100 之间");
        }
        return value;
    }

    private String validateDescription(String description) {
        if (description == null) {
            return null;
        }
        String value = description.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > DESCRIPTION_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "工作空间描述不能超过 500 个字符");
        }
        return value;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
