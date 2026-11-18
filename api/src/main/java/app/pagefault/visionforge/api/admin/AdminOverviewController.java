package app.pagefault.visionforge.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.admin.dto.AdminOverviewResponse;
import app.pagefault.visionforge.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/overview")
@RequiredArgsConstructor
public class AdminOverviewController {

    private final AdminOverviewService adminOverviewService;

    @GetMapping
    public ApiResponse<AdminOverviewResponse> overview() {
        return ApiResponse.success(adminOverviewService.snapshot());
    }
}
