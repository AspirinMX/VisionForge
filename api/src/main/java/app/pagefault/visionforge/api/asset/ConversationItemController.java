package app.pagefault.visionforge.api.asset;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.conversation.ConversationService;
import app.pagefault.visionforge.api.asset.dto.BindConversationItemRequest;
import app.pagefault.visionforge.api.asset.dto.ConversationItemResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.api.security.WorkspaceRequestGuard;
import app.pagefault.visionforge.asset.library.ConversationItem;
import app.pagefault.visionforge.asset.library.ConversationItemView;
import app.pagefault.visionforge.asset.library.WorkspaceItemService;
import app.pagefault.visionforge.common.api.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/conversations/{conversationId}/items")
@RequiredArgsConstructor
public class ConversationItemController {

    private final WorkspaceItemService workspaceItemService;
    private final ConversationService conversationService;
    private final WorkspaceRequestGuard workspaceRequestGuard;

    @GetMapping
    public ApiResponse<List<ConversationItemResponse>> list(
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        conversationService.get(workspaceId, conversationId);
        List<ConversationItemView> items = workspaceItemService.listConversationItems(
                workspaceId,
                AuthenticatedUser.id(jwt),
                AuthenticatedUser.platformAdmin(jwt),
                conversationId,
                limit);
        return ApiResponse.success(items.stream().map(ConversationItemResponse::from).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationItemResponse> bind(
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody BindConversationItemRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        conversationService.get(workspaceId, conversationId);
        ConversationItem binding = workspaceItemService.bind(
                workspaceId,
                AuthenticatedUser.id(jwt),
                AuthenticatedUser.platformAdmin(jwt),
                conversationId,
                request.itemId(),
                request.role(),
                request.pinnedVersion());
        ConversationItemView view = new ConversationItemView(
                binding,
                workspaceItemService.get(
                        workspaceId,
                        AuthenticatedUser.id(jwt),
                        AuthenticatedUser.platformAdmin(jwt),
                        request.itemId()));
        return ApiResponse.success(ConversationItemResponse.from(view));
    }

    @DeleteMapping("/{itemId}")
    public ApiResponse<Void> unbind(
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @PathVariable UUID itemId,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        conversationService.get(workspaceId, conversationId);
        workspaceItemService.unbind(
                workspaceId,
                AuthenticatedUser.id(jwt),
                AuthenticatedUser.platformAdmin(jwt),
                conversationId,
                itemId);
        return ApiResponse.success();
    }
}
