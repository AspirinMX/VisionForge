package app.pagefault.visionforge.api.asset;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.asset.dto.AssetResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.asset.media.AssetKind;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.asset.media.AssetSource;
import app.pagefault.visionforge.common.api.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * Read-only asset catalog of one conversation. The collection endpoint replaces the client-side
 * placeholder cache, and the single-asset path lets a user resolve a pasted id with the same
 * conversation isolation rules.
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/assets")
@RequiredArgsConstructor
public class ConversationAssetController {

    private final AssetService assetService;

    @GetMapping
    public ApiResponse<List<AssetResponse>> list(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) AssetKind kind,
            @RequestParam(required = false) AssetSource source,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal Jwt jwt) {
        List<AssetResponse> assets = assetService
                .list(
                        AuthenticatedUser.id(jwt),
                        conversationId,
                        kind,
                        source,
                        limit == null ? 50 : limit)
                .stream()
                .map(AssetResponse::from)
                .toList();
        return ApiResponse.success(assets);
    }

    @GetMapping("/{assetId}")
    public ApiResponse<AssetResponse> get(
            @PathVariable UUID conversationId,
            @PathVariable UUID assetId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(AssetResponse.from(assetService.getForConversation(
                AuthenticatedUser.id(jwt), conversationId, assetId)));
    }
}
