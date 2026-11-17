package app.pagefault.visionforge.api.asset;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.asset.dto.CreateWorkspaceItemRequest;
import app.pagefault.visionforge.api.asset.dto.UpdateWorkspaceItemRequest;
import app.pagefault.visionforge.api.asset.dto.WorkspaceItemResponse;
import app.pagefault.visionforge.api.asset.dto.WorkspaceItemVersionResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.api.security.WorkspaceRequestGuard;
import app.pagefault.visionforge.agent.conversation.ConversationService;
import app.pagefault.visionforge.asset.library.CreateWorkspaceItemCommand;
import app.pagefault.visionforge.asset.library.UpdateWorkspaceItemCommand;
import app.pagefault.visionforge.asset.library.WorkspaceItem;
import app.pagefault.visionforge.asset.library.WorkspaceItemKind;
import app.pagefault.visionforge.asset.library.WorkspaceItemService;
import app.pagefault.visionforge.asset.library.WorkspaceItemSource;
import app.pagefault.visionforge.asset.library.WorkspaceItemVersion;
import app.pagefault.visionforge.common.api.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/items")
@RequiredArgsConstructor
public class WorkspaceItemController {

    private final WorkspaceItemService workspaceItemService;
    private final ConversationService conversationService;
    private final WorkspaceRequestGuard workspaceRequestGuard;

    @GetMapping
    public ApiResponse<List<WorkspaceItemResponse>> list(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) WorkspaceItemKind kind,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        UUID userId = AuthenticatedUser.id(jwt);
        boolean platformAdmin = AuthenticatedUser.platformAdmin(jwt);
        List<WorkspaceItem> items = q == null || q.isBlank()
                ? workspaceItemService.list(workspaceId, userId, platformAdmin, kind, limit)
                : workspaceItemService.search(
                        workspaceId, userId, platformAdmin, q, kind, limit);
        return ApiResponse.success(items.stream().map(WorkspaceItemResponse::from).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkspaceItemResponse> create(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateWorkspaceItemRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        if (request.conversationId() != null) {
            conversationService.get(workspaceId, request.conversationId());
        }
        WorkspaceItem item = workspaceItemService.create(
                workspaceId,
                AuthenticatedUser.id(jwt),
                new CreateWorkspaceItemCommand(
                        request.kind(),
                        request.title(),
                        request.path(),
                        request.content(),
                        request.visibility(),
                        request.conversationId(),
                        request.role(),
                        WorkspaceItemSource.USER_UPLOAD));
        return ApiResponse.success(WorkspaceItemResponse.from(item));
    }

    @GetMapping("/{itemId}")
    public ApiResponse<WorkspaceItemResponse> get(
            @PathVariable UUID workspaceId,
            @PathVariable UUID itemId,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        WorkspaceItem item = workspaceItemService.get(
                workspaceId,
                AuthenticatedUser.id(jwt),
                AuthenticatedUser.platformAdmin(jwt),
                itemId);
        return ApiResponse.success(WorkspaceItemResponse.from(item));
    }

    @GetMapping("/{itemId}/versions")
    public ApiResponse<List<WorkspaceItemVersionResponse>> listVersions(
            @PathVariable UUID workspaceId,
            @PathVariable UUID itemId,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        List<WorkspaceItemVersion> versions = workspaceItemService.listVersions(
                workspaceId,
                AuthenticatedUser.id(jwt),
                AuthenticatedUser.platformAdmin(jwt),
                itemId,
                limit);
        return ApiResponse.success(versions.stream()
                .map(version -> WorkspaceItemVersionResponse.from(version, false))
                .toList());
    }

    @GetMapping("/{itemId}/versions/{version}")
    public ApiResponse<WorkspaceItemVersionResponse> getVersion(
            @PathVariable UUID workspaceId,
            @PathVariable UUID itemId,
            @PathVariable int version,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        WorkspaceItemVersion result = workspaceItemService.readVersion(
                workspaceId,
                AuthenticatedUser.id(jwt),
                AuthenticatedUser.platformAdmin(jwt),
                itemId,
                version);
        return ApiResponse.success(WorkspaceItemVersionResponse.from(result, true));
    }

    @PatchMapping("/{itemId}")
    public ApiResponse<WorkspaceItemResponse> update(
            @PathVariable UUID workspaceId,
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateWorkspaceItemRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        WorkspaceItem item = workspaceItemService.update(
                workspaceId,
                AuthenticatedUser.id(jwt),
                AuthenticatedUser.platformAdmin(jwt),
                itemId,
                new UpdateWorkspaceItemCommand(
                        request.baseVersion(),
                        request.content(),
                        request.title(),
                        WorkspaceItemSource.USER_UPLOAD));
        return ApiResponse.success(WorkspaceItemResponse.from(item));
    }
}
