package app.pagefault.visionforge.api.asset;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.asset.dto.AssetDownloadUrlResponse;
import app.pagefault.visionforge.api.asset.dto.AssetResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @GetMapping("/{assetId}")
    public ApiResponse<AssetResponse> get(
            @PathVariable UUID assetId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(AssetResponse.from(
                assetService.get(AuthenticatedUser.id(jwt), assetId)));
    }

    @GetMapping("/{assetId}/download-url")
    public ApiResponse<AssetDownloadUrlResponse> downloadUrl(
            @PathVariable UUID assetId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(AssetDownloadUrlResponse.from(
                assetService.createDownloadUrl(AuthenticatedUser.id(jwt), assetId)));
    }
}
