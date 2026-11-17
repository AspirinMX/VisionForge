package app.pagefault.visionforge.api.agent;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.agent.runtime.AgentRun;
import app.pagefault.visionforge.agent.runtime.AgentRunService;
import app.pagefault.visionforge.api.agent.dto.AgentRunResponse;
import app.pagefault.visionforge.api.security.WorkspaceRequestGuard;
import app.pagefault.visionforge.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/agent-runs")
@RequiredArgsConstructor
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final WorkspaceRequestGuard workspaceRequestGuard;

    @GetMapping("/{runId}")
    public ApiResponse<AgentRunResponse> get(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt) {
        workspaceRequestGuard.require(jwt, workspaceId);
        AgentRun run = agentRunService.getForWorkspace(workspaceId, runId);
        return ApiResponse.success(AgentRunResponse.from(run));
    }
}
