package app.pagefault.visionforge.api.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.identity.dto.AddMemberRequest;
import app.pagefault.visionforge.api.identity.dto.ChangeMemberRoleRequest;
import app.pagefault.visionforge.api.identity.dto.CreateWorkspaceRequest;
import app.pagefault.visionforge.api.identity.dto.MemberResponse;
import app.pagefault.visionforge.api.identity.dto.TransferOwnershipRequest;
import app.pagefault.visionforge.api.identity.dto.UpdateWorkspaceRequest;
import app.pagefault.visionforge.api.identity.dto.WorkspaceResponse;
import app.pagefault.visionforge.api.identity.dto.WorkspaceSummaryResponse;
import app.pagefault.visionforge.api.security.WorkspaceRequestGuard;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.identity.workspace.Workspace;
import app.pagefault.visionforge.identity.workspace.WorkspaceMemberView;
import app.pagefault.visionforge.identity.workspace.WorkspaceRole;
import app.pagefault.visionforge.identity.workspace.WorkspaceService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceRequestGuard workspaceRequestGuard;

    @GetMapping
    public ApiResponse<List<WorkspaceSummaryResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        List<WorkspaceSummaryResponse> workspaces = workspaceService
                .listForUser(AuthenticatedUser.id(jwt))
                .stream()
                .map(WorkspaceSummaryResponse::from)
                .toList();
        return ApiResponse.success(workspaces);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkspaceResponse> create(
            @Valid @RequestBody CreateWorkspaceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Workspace workspace = workspaceService.create(
                AuthenticatedUser.actor(jwt), request.name(), request.description());
        return ApiResponse.success(WorkspaceResponse.from(workspace, WorkspaceRole.OWNER));
    }

    @GetMapping("/{workspaceId}")
    public ApiResponse<WorkspaceResponse> get(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        Workspace workspace = workspaceService.get(AuthenticatedUser.actor(jwt), workspaceId);
        return ApiResponse.success(
                WorkspaceResponse.from(workspace, AuthenticatedUser.workspaceRole(jwt)));
    }

    @PatchMapping("/{workspaceId}")
    public ApiResponse<WorkspaceResponse> update(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWorkspaceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        Workspace workspace = workspaceService.update(
                AuthenticatedUser.actor(jwt), workspaceId, request.name(), request.description());
        return ApiResponse.success(
                WorkspaceResponse.from(workspace, AuthenticatedUser.workspaceRole(jwt)));
    }

    @PostMapping("/{workspaceId}/archive")
    public ApiResponse<Void> archive(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        workspaceService.archive(AuthenticatedUser.actor(jwt), workspaceId);
        return ApiResponse.success();
    }

    @PostMapping("/{workspaceId}/transfer")
    public ApiResponse<MemberResponse> transfer(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody TransferOwnershipRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        WorkspaceMemberView member = workspaceService.transferOwnership(
                AuthenticatedUser.actor(jwt), workspaceId, request.userId());
        return ApiResponse.success(MemberResponse.from(member));
    }

    @GetMapping("/{workspaceId}/members")
    public ApiResponse<List<MemberResponse>> listMembers(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        List<MemberResponse> members = workspaceService
                .listMembers(AuthenticatedUser.actor(jwt), workspaceId)
                .stream()
                .map(MemberResponse::from)
                .toList();
        return ApiResponse.success(members);
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MemberResponse> addMember(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody AddMemberRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        WorkspaceMemberView member = workspaceService.addMember(
                AuthenticatedUser.actor(jwt), workspaceId, request.email(), request.role());
        return ApiResponse.success(MemberResponse.from(member));
    }

    @PatchMapping("/{workspaceId}/members/{userId}")
    public ApiResponse<MemberResponse> changeMemberRole(
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeMemberRoleRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        WorkspaceMemberView member = workspaceService.changeMemberRole(
                AuthenticatedUser.actor(jwt), workspaceId, userId, request.role());
        return ApiResponse.success(MemberResponse.from(member));
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    public ApiResponse<Void> removeMember(
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        workspaceService.removeMember(AuthenticatedUser.actor(jwt), workspaceId, userId);
        return ApiResponse.success();
    }
}
