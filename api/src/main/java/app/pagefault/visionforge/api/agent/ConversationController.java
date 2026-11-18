package app.pagefault.visionforge.api.agent;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import app.pagefault.visionforge.agent.conversation.AgentTurnResult;
import app.pagefault.visionforge.agent.conversation.Conversation;
import app.pagefault.visionforge.agent.conversation.ConversationService;
import app.pagefault.visionforge.api.agent.dto.AgentTurnResponse;
import app.pagefault.visionforge.api.agent.dto.ConversationResponse;
import app.pagefault.visionforge.api.agent.dto.CreateConversationRequest;
import app.pagefault.visionforge.api.agent.dto.MessageResponse;
import app.pagefault.visionforge.api.agent.dto.SendMessageRequest;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.api.stream.AgentRunStreamService;
import app.pagefault.visionforge.common.api.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final AgentRunStarter agentRunStarter;
    private final AgentRunStreamService agentRunStreamService;

    @GetMapping
    public ApiResponse<List<ConversationResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        List<ConversationResponse> conversations =
                conversationService.list(AuthenticatedUser.id(jwt)).stream()
                        .map(ConversationResponse::from)
                        .toList();
        return ApiResponse.success(conversations);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> create(
            @Valid @RequestBody CreateConversationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Conversation conversation = conversationService.create(
                AuthenticatedUser.id(jwt), request.title());
        return ApiResponse.success(ConversationResponse.from(conversation));
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationResponse> get(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(ConversationResponse.from(
                conversationService.get(AuthenticatedUser.id(jwt), conversationId)));
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<MessageResponse>> messages(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal Jwt jwt) {
        List<MessageResponse> messages = conversationService
                .messagesWithParts(AuthenticatedUser.id(jwt), conversationId)
                .stream()
                .map(MessageResponse::from)
                .toList();
        return ApiResponse.success(messages);
    }

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AgentTurnResponse> send(
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        AgentTurnResult result = conversationService.startTurn(
                AuthenticatedUser.id(jwt), conversationId, request.content(), request.assetIds());
        agentRunStarter.start(result.runId(), result.workflowId());
        return ApiResponse.success(AgentTurnResponse.from(result));
    }

    @PostMapping(
            value = "/{conversationId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendStream(
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        AgentTurnResult result = conversationService.startTurn(
                AuthenticatedUser.id(jwt), conversationId, request.content(), request.assetIds());
        SseEmitter emitter = agentRunStreamService.subscribe(result.runId());
        agentRunStarter.start(result.runId(), result.workflowId());
        return emitter;
    }
}
