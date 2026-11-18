package app.pagefault.visionforge.api.asset;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.pagefault.visionforge.api.asset.dto.ConversationFileResponse;
import app.pagefault.visionforge.api.asset.dto.ConversationFileVersionResponse;
import app.pagefault.visionforge.api.asset.dto.CreateConversationFileRequest;
import app.pagefault.visionforge.api.asset.dto.UpdateConversationFileRequest;
import app.pagefault.visionforge.agent.assetlink.ConversationFileAssetResolver;
import app.pagefault.visionforge.agent.storyboard.StoryboardValidator;
import app.pagefault.visionforge.api.identity.AuthenticatedUser;
import app.pagefault.visionforge.asset.library.ConversationFile;
import app.pagefault.visionforge.asset.library.ConversationFileKind;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationFileSource;
import app.pagefault.visionforge.asset.library.ConversationFileVersion;
import app.pagefault.visionforge.asset.library.CreateConversationFileCommand;
import app.pagefault.visionforge.asset.library.UpdateConversationFileCommand;
import app.pagefault.visionforge.common.api.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Files of one conversation's work area. Every operation resolves the owner from the token and
 * rejects conversations that belong to another user.
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/files")
@RequiredArgsConstructor
public class ConversationFileController {

    private static final int DEFAULT_LIMIT = 50;

    private final ConversationFileService conversationFileService;
    private final StoryboardValidator storyboardValidator;
    private final ConversationFileAssetResolver assetResolver;

    @GetMapping
    public ApiResponse<List<ConversationFileResponse>> list(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) ConversationFileKind kind,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal Jwt jwt) {
        List<ConversationFileResponse> files = conversationFileService
                .list(
                        AuthenticatedUser.id(jwt),
                        conversationId,
                        kind,
                        limit == null ? DEFAULT_LIMIT : limit)
                .stream()
                .map(ConversationFileResponse::from)
                .toList();
        return ApiResponse.success(files);
    }

    @GetMapping("/search")
    public ApiResponse<List<ConversationFileResponse>> search(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ConversationFileKind kind,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal Jwt jwt) {
        List<ConversationFileResponse> files = conversationFileService
                .search(
                        AuthenticatedUser.id(jwt),
                        conversationId,
                        q,
                        kind,
                        limit == null ? DEFAULT_LIMIT : limit)
                .stream()
                .map(ConversationFileResponse::from)
                .toList();
        return ApiResponse.success(files);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationFileResponse> create(
            @PathVariable UUID conversationId,
            @Valid @RequestBody CreateConversationFileRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (request.kind() == ConversationFileKind.STORYBOARD) {
            storyboardValidator.validate(request.content());
        }
        ConversationFile file = conversationFileService.create(
                AuthenticatedUser.id(jwt),
                conversationId,
                new CreateConversationFileCommand(
                        request.kind(),
                        request.title(),
                        request.path(),
                        request.content(),
                        ConversationFileSource.USER_UPLOAD));
        return ApiResponse.success(ConversationFileResponse.from(file));
    }

    @GetMapping("/{fileId}")
    public ApiResponse<ConversationFileResponse> get(
            @PathVariable UUID conversationId,
            @PathVariable UUID fileId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(ConversationFileResponse.from(
                conversationFileService.get(AuthenticatedUser.id(jwt), conversationId, fileId)));
    }

    @PatchMapping("/{fileId}")
    public ApiResponse<ConversationFileResponse> update(
            @PathVariable UUID conversationId,
            @PathVariable UUID fileId,
            @Valid @RequestBody UpdateConversationFileRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ConversationFile existing = conversationFileService.get(
                AuthenticatedUser.id(jwt), conversationId, fileId);
        if (existing.getKind() == ConversationFileKind.STORYBOARD) {
            storyboardValidator.validate(request.content());
        }
        ConversationFile file = conversationFileService.update(
                AuthenticatedUser.id(jwt),
                conversationId,
                fileId,
                new UpdateConversationFileCommand(
                        request.baseVersion(),
                        request.content(),
                        request.title(),
                        ConversationFileSource.USER_UPLOAD));
        return ApiResponse.success(ConversationFileResponse.from(file));
    }

    @GetMapping("/{fileId}/versions")
    public ApiResponse<List<ConversationFileVersionResponse>> versions(
            @PathVariable UUID conversationId,
            @PathVariable UUID fileId,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = AuthenticatedUser.id(jwt);
        List<ConversationFileVersion> versions = conversationFileService.listVersions(
                userId,
                conversationId,
                fileId,
                limit == null ? DEFAULT_LIMIT : limit);
        Map<UUID, UUID> assetIds =
                assetResolver.resolve(userId, conversationId, versions);
        return ApiResponse.success(versions.stream()
                .map(version -> ConversationFileVersionResponse.from(
                        version, assetIds.get(version.getId())))
                .toList());
    }

    @GetMapping("/{fileId}/versions/{version}")
    public ApiResponse<ConversationFileVersionResponse> version(
            @PathVariable UUID conversationId,
            @PathVariable UUID fileId,
            @PathVariable int version,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = AuthenticatedUser.id(jwt);
        ConversationFileVersion versionEntity = conversationFileService.readVersion(
                userId, conversationId, fileId, version);
        Map<UUID, UUID> assetIds = assetResolver.resolve(
                userId, conversationId, List.of(versionEntity));
        return ApiResponse.success(ConversationFileVersionResponse.from(
                versionEntity, assetIds.get(versionEntity.getId())));
    }
}
