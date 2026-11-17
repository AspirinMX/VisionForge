package app.pagefault.visionforge.api.agent;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.agent.dto.AgentTurnResponse;
import app.pagefault.visionforge.api.agent.dto.ConversationResponse;
import app.pagefault.visionforge.api.agent.dto.CreateConversationRequest;
import app.pagefault.visionforge.api.agent.dto.MessageResponse;
import app.pagefault.visionforge.api.agent.dto.SendMessageRequest;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.api.security.WorkspaceRequestGuard;
import app.pagefault.visionforge.agent.conversation.AgentTurnResult;
import app.pagefault.visionforge.agent.conversation.Conversation;
import app.pagefault.visionforge.agent.conversation.ConversationService;
import app.pagefault.visionforge.common.api.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final AgentRunStarter agentRunStarter;
    private final WorkspaceRequestGuard workspaceRequestGuard;

    @GetMapping
    public ApiResponse<List<ConversationResponse>> list(
            @PathVariable UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        List<ConversationResponse> conversations = conversationService.list(workspaceId).stream()
                .map(ConversationResponse::from)
                .toList();
        return ApiResponse.success(conversations);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> create(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateConversationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        Conversation conversation = conversationService.create(
                workspaceId, AuthenticatedUser.id(jwt), request.title());
        return ApiResponse.success(ConversationResponse.from(conversation));
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationResponse> get(
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        return ApiResponse.success(
                ConversationResponse.from(conversationService.get(workspaceId, conversationId)));
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<MessageResponse>> messages(
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        List<MessageResponse> messages = conversationService
                .messages(workspaceId, conversationId)
                .stream()
                .map(MessageResponse::from)
                .toList();
        return ApiResponse.success(messages);
    }

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AgentTurnResponse> send(
            @PathVariable UUID workspaceId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        AgentTurnResult result = conversationService.startTurn(
                workspaceId, conversationId, AuthenticatedUser.id(jwt), request.content());
        agentRunStarter.start(result.runId(), result.workflowId());
        return ApiResponse.success(AgentTurnResponse.from(result));
    }
}
