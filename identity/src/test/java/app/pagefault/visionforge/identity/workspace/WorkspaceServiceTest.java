package app.pagefault.visionforge.identity.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;
import app.pagefault.visionforge.identity.error.IdentityErrorCode;
import app.pagefault.visionforge.identity.user.UserAccount;
import app.pagefault.visionforge.identity.user.UserMapper;
import app.pagefault.visionforge.identity.user.UserRole;
import app.pagefault.visionforge.identity.user.UserStatus;

class WorkspaceServiceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final UUID ACTOR_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID TARGET_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e31");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private WorkspaceMapper workspaceMapper;
    private WorkspaceMemberMapper memberMapper;
    private UserMapper userMapper;
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        workspaceMapper = mock(WorkspaceMapper.class);
        memberMapper = mock(WorkspaceMemberMapper.class);
        userMapper = mock(UserMapper.class);
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new WorkspaceService(
                workspaceMapper, memberMapper, userMapper, idGenerator, clock);
    }

    @Test
    void createAddsOwnerMembership() {
        Workspace workspace = service.create(new WorkspaceActor(ACTOR_ID, false), "Studio", "Desc");

        assertThat(workspace.getId()).isNotNull();
        assertThat(workspace.getName()).isEqualTo("Studio");
        assertThat(workspace.getStatus()).isEqualTo(WorkspaceStatus.ACTIVE);
        assertThat(workspace.isPersonal()).isFalse();

        ArgumentCaptor<WorkspaceMember> captor = ArgumentCaptor.forClass(WorkspaceMember.class);
        verify(memberMapper).insert(captor.capture());
        WorkspaceMember member = captor.getValue();
        assertThat(member.getWorkspaceId()).isEqualTo(workspace.getId());
        assertThat(member.getUserId()).isEqualTo(ACTOR_ID);
        assertThat(member.getRole()).isEqualTo(WorkspaceRole.OWNER);
        assertThat(member.getStatus()).isEqualTo(WorkspaceMemberStatus.ACTIVE);
    }

    @Test
    void personalWorkspaceIsOwnedByTheUser() {
        Workspace workspace = service.createPersonalWorkspace(ACTOR_ID);

        assertThat(workspace.isPersonal()).isTrue();
        assertThat(workspace.getName()).isEqualTo("个人空间");

        ArgumentCaptor<WorkspaceMember> captor = ArgumentCaptor.forClass(WorkspaceMember.class);
        verify(memberMapper).insert(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(WorkspaceRole.OWNER);
        assertThat(captor.getValue().getUserId()).isEqualTo(ACTOR_ID);
    }

    @Test
    void nonMemberCannotReadWorkspace() {
        Workspace workspace = activeWorkspace();
        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace);
        when(memberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.get(new WorkspaceActor(ACTOR_ID, false), WORKSPACE_ID))
                .satisfies(exception -> assertError(exception, IdentityErrorCode.WORKSPACE_ACCESS_DENIED));
    }

    @Test
    void platformAdministratorBypassesMembership() {
        Workspace workspace = activeWorkspace();
        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace);

        Workspace result = service.get(new WorkspaceActor(ACTOR_ID, true), WORKSPACE_ID);

        assertThat(result).isSameAs(workspace);
        verify(memberMapper, never()).selectOne(any());
    }

    @Test
    void archivedWorkspaceIsNotAccessible() {
        Workspace workspace = activeWorkspace();
        workspace.setStatus(WorkspaceStatus.ARCHIVED);
        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace);

        assertThatThrownBy(() -> service.get(new WorkspaceActor(ACTOR_ID, true), WORKSPACE_ID))
                .satisfies(exception -> assertError(exception, IdentityErrorCode.WORKSPACE_ARCHIVED));
    }

    @Test
    void ownerCannotBeRemoved() {
        Workspace workspace = activeWorkspace();
        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace);
        when(memberMapper.selectOne(any()))
                .thenReturn(member(ACTOR_ID, WorkspaceRole.OWNER))
                .thenReturn(member(TARGET_ID, WorkspaceRole.OWNER));

        assertThatThrownBy(() -> service.removeMember(
                new WorkspaceActor(ACTOR_ID, false), WORKSPACE_ID, TARGET_ID))
                .satisfies(exception -> assertError(exception, IdentityErrorCode.CANNOT_REMOVE_OWNER));

        verify(memberMapper, never()).deleteById(any(UUID.class));
    }

    @Test
    void workspaceAdministratorCannotRemoveAnotherAdministrator() {
        Workspace workspace = activeWorkspace();
        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace);
        when(memberMapper.selectOne(any()))
                .thenReturn(member(ACTOR_ID, WorkspaceRole.ADMIN))
                .thenReturn(member(TARGET_ID, WorkspaceRole.ADMIN));

        assertThatThrownBy(() -> service.removeMember(
                new WorkspaceActor(ACTOR_ID, false), WORKSPACE_ID, TARGET_ID))
                .satisfies(exception -> assertError(exception, IdentityErrorCode.WORKSPACE_ACCESS_DENIED));
    }

    @Test
    void transferDemotesCurrentOwnerBeforePromotingTarget() {
        Workspace workspace = activeWorkspace();
        WorkspaceMember actorOwner = member(ACTOR_ID, WorkspaceRole.OWNER);
        WorkspaceMember targetAdmin = member(TARGET_ID, WorkspaceRole.ADMIN);
        WorkspaceMember currentOwner = member(ACTOR_ID, WorkspaceRole.OWNER);

        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace);
        when(memberMapper.selectOne(any()))
                .thenReturn(actorOwner)
                .thenReturn(targetAdmin)
                .thenReturn(currentOwner);

        service.transferOwnership(
                new WorkspaceActor(ACTOR_ID, false), WORKSPACE_ID, TARGET_ID);

        ArgumentCaptor<WorkspaceMember> captor = ArgumentCaptor.forClass(WorkspaceMember.class);
        verify(memberMapper, times(2)).updateById(captor.capture());
        List<WorkspaceMember> updates = captor.getAllValues();
        assertThat(updates.get(0).getUserId()).isEqualTo(ACTOR_ID);
        assertThat(updates.get(0).getRole()).isEqualTo(WorkspaceRole.ADMIN);
        assertThat(updates.get(1).getUserId()).isEqualTo(TARGET_ID);
        assertThat(updates.get(1).getRole()).isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    void workspaceAdministratorCannotGrantAdministrator() {
        Workspace workspace = activeWorkspace();
        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace);
        when(memberMapper.selectOne(any())).thenReturn(member(ACTOR_ID, WorkspaceRole.ADMIN));

        assertThatThrownBy(() -> service.addMember(
                new WorkspaceActor(ACTOR_ID, false), WORKSPACE_ID, "bob@example.com", WorkspaceRole.ADMIN))
                .satisfies(exception -> assertError(exception, IdentityErrorCode.WORKSPACE_ACCESS_DENIED));
    }

    @Test
    void addMemberRejectsExistingUser() {
        Workspace workspace = activeWorkspace();
        UserAccount user = activeUser(TARGET_ID, "bob@example.com");

        when(workspaceMapper.selectById(WORKSPACE_ID)).thenReturn(workspace);
        when(memberMapper.selectOne(any()))
                .thenReturn(member(ACTOR_ID, WorkspaceRole.ADMIN))
                .thenReturn(member(TARGET_ID, WorkspaceRole.MEMBER));
        when(userMapper.selectOne(any())).thenReturn(user);

        assertThatThrownBy(() -> service.addMember(
                new WorkspaceActor(ACTOR_ID, false), WORKSPACE_ID, "bob@example.com", WorkspaceRole.MEMBER))
                .satisfies(exception -> assertError(exception, IdentityErrorCode.MEMBER_ALREADY_EXISTS));
    }

    private Workspace activeWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setName("Studio");
        workspace.setStatus(WorkspaceStatus.ACTIVE);
        workspace.setCreatedAt(NOW);
        workspace.setUpdatedAt(NOW);
        return workspace;
    }

    private WorkspaceMember member(UUID userId, WorkspaceRole role) {
        WorkspaceMember member = new WorkspaceMember();
        member.setId(UUID.randomUUID());
        member.setWorkspaceId(WORKSPACE_ID);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(WorkspaceMemberStatus.ACTIVE);
        member.setJoinedAt(NOW);
        member.setUpdatedAt(NOW);
        return member;
    }

    private UserAccount activeUser(UUID id, String email) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setEmail(email);
        user.setDisplayName("Bob");
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private void assertError(Throwable exception, IdentityErrorCode errorCode) {
        assertThat(exception).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(errorCode);
    }
}
