package app.pagefault.visionforge.api.identity;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.identity.dto.WorkspaceResponse;
import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.identity.workspace.Workspace;
import app.pagefault.visionforge.identity.workspace.WorkspaceService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/workspaces")
@RequiredArgsConstructor
public class AdminWorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public ApiResponse<PageResult<WorkspaceResponse>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        PageResult<Workspace> workspaces = workspaceService.listAll(page, size);
        PageResult<WorkspaceResponse> response = PageResult.of(
                workspaces.items().stream()
                        .map(workspace -> WorkspaceResponse.from(workspace, null))
                        .toList(),
                workspaces.page(),
                workspaces.size(),
                workspaces.total());
        return ApiResponse.success(response);
    }

    @GetMapping("/{workspaceId}")
    public ApiResponse<WorkspaceResponse> get(@PathVariable UUID workspaceId) {
        Workspace workspace = workspaceService.getForAdmin(workspaceId);
        return ApiResponse.success(WorkspaceResponse.from(workspace, null));
    }
}
