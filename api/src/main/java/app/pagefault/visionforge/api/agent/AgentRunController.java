package app.pagefault.visionforge.api.agent;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.agent.runtime.AgentRunStatus;
import app.pagefault.visionforge.api.agent.dto.AgentRunResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.asset.library.ConversationReferenceChecker;
import app.pagefault.visionforge.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/agent-runs")
@RequiredArgsConstructor
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final ConversationReferenceChecker conversationReferenceChecker;

    /**
     * Newest-first runs, optionally filtered by conversation and status. Combined with the status
     * filter this lets the studio recover an in-flight run after a reload.
     */
    @GetMapping
    public ApiResponse<List<AgentRunResponse>> list(
            @RequestParam(required = false) UUID conversationId,
            @RequestParam(required = false) List<AgentRunStatus> status,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = AuthenticatedUser.id(jwt);
        if (conversationId != null) {
            conversationReferenceChecker.requireOwned(userId, conversationId);
        }
        return ApiResponse.success(
                agentRunService.list(userId, conversationId, status, limit).stream()
                        .map(AgentRunResponse::from)
                        .toList());
    }

    @GetMapping("/{runId}")
    public ApiResponse<AgentRunResponse> get(
            @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt) {
        AgentRun run = agentRunService.getForUser(AuthenticatedUser.id(jwt), runId);
        return ApiResponse.success(AgentRunResponse.from(run));
    }
}
