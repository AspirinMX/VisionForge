package app.pagefault.visionforge.api.asset;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.asset.dto.AssetResponse;
import app.pagefault.visionforge.api.asset.dto.CreateUploadRequest;
import app.pagefault.visionforge.api.asset.dto.UploadTicketResponse;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.asset.media.AssetService;
import app.pagefault.visionforge.asset.upload.CreateUploadCommand;
import app.pagefault.visionforge.common.api.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Direct-to-object-storage uploads scoped to one conversation. The client uploads bytes to the
 * returned presigned URL and then asks the server to verify the object.
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final AssetService assetService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UploadTicketResponse> create(
            @PathVariable UUID conversationId,
            @Valid @RequestBody CreateUploadRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(UploadTicketResponse.from(assetService.createUpload(
                AuthenticatedUser.id(jwt),
                conversationId,
                new CreateUploadCommand(
                        request.kind(),
                        request.filename(),
                        request.mimeType(),
                        request.sizeBytes()))));
    }

    @PostMapping("/{uploadSessionId}/complete")
    public ApiResponse<AssetResponse> complete(
            @PathVariable UUID conversationId,
            @PathVariable UUID uploadSessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(AssetResponse.from(assetService.completeUpload(
                AuthenticatedUser.id(jwt), conversationId, uploadSessionId)));
    }
}
