package app.pagefault.visionforge.agent.storyboard;

import java.util.UUID;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.asset.library.ConversationFile;
import app.pagefault.visionforge.asset.library.ConversationFileKind;
import app.pagefault.visionforge.asset.library.ConversationFileService;
import app.pagefault.visionforge.asset.library.ConversationFileSource;
import app.pagefault.visionforge.asset.library.ConversationFileVersion;
import app.pagefault.visionforge.asset.library.UpdateConversationFileCommand;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and mutates storyboard files while keeping the conversation-file version model. All
 * mutations create a new version; retries re-read the latest version before applying the change.
 */
@RequiredArgsConstructor
public class StoryboardService {

    private static final int WRITE_RETRIES = 2;

    private final ConversationFileService fileService;
    private final StoryboardValidator validator;
    private final ObjectMapper objectMapper;

    public StoryboardSummary validateFile(
            UUID userId, UUID conversationId, UUID fileId, Integer version) {
        ConversationFile file = requireStoryboard(userId, conversationId, fileId);
        ConversationFileVersion content =
                fileService.readVersion(userId, conversationId, file.getId(), version);
        return validator.validate(content.getContent());
    }

    /**
     * Resolves the shot a generation is attached to. The returned version is frozen on the
     * generation task; a null requested version means the current storyboard version.
     */
    public ResolvedDocument resolveDocument(
            UUID userId, UUID conversationId, UUID fileId, Integer version) {
        requireStoryboard(userId, conversationId, fileId);
        ConversationFileVersion content =
                fileService.readVersion(userId, conversationId, fileId, version);
        StoryboardDocument document = validator.parse(content.getContent());
        return new ResolvedDocument(document, content.getVersion());
    }

    public ResolvedShot resolveShot(
            UUID userId,
            UUID conversationId,
            UUID fileId,
            Integer version,
            String shotId) {
        requireStoryboard(userId, conversationId, fileId);
        ConversationFileVersion content =
                fileService.readVersion(userId, conversationId, fileId, version);
        StoryboardDocument document = validator.parse(content.getContent());
        StoryboardShot shot = document.shots().stream()
                .filter(candidate -> candidate.id().equals(shotId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        AgentErrorCode.STORYBOARD_SHOT_NOT_FOUND));
        return new ResolvedShot(shot, content.getVersion(), document);
    }

    public record ResolvedShot(
            StoryboardShot shot, int storyboardVersion, StoryboardDocument document) {
    }

    public record ResolvedDocument(StoryboardDocument document, int storyboardVersion) {
    }

    public void recordFirstFrame(
            UUID userId,
            UUID conversationId,
            UUID fileId,
            String shotId,
            UUID assetId,
            UUID outputFileId,
            int outputVersion) {
        updateShot(userId, conversationId, fileId, shotId, shot -> {
            ObjectNode firstFrame = objectMapper.createObjectNode()
                    .put("assetId", assetId.toString())
                    .put("fileId", outputFileId.toString())
                    .put("version", outputVersion);
            shot.set("firstFrame", firstFrame);
            shot.put("status", StoryboardShotStatus.FIRST_FRAME_READY.name());
        });
    }

    public void recordVideoCandidate(
            UUID userId,
            UUID conversationId,
            UUID fileId,
            String shotId,
            UUID taskId,
            UUID assetId,
            UUID outputFileId,
            int outputVersion) {
        updateShot(userId, conversationId, fileId, shotId, shot -> {
            ArrayNode candidates = array(shot, "videoCandidates");
            boolean exists = false;
            for (JsonNode candidate : candidates) {
                if (taskId.toString().equals(candidate.path("taskId").asText())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                candidates.add(objectMapper.createObjectNode()
                        .put("taskId", taskId.toString())
                        .put("assetId", assetId.toString())
                        .put("fileId", outputFileId.toString())
                        .put("version", outputVersion)
                        .put("status", "SUCCEEDED"));
            }
            JsonNode selected = shot.path("selectedVideo");
            if (selected.isMissingNode() || selected.isNull()) {
                shot.set("selectedVideo", objectMapper.createObjectNode()
                        .put("taskId", taskId.toString())
                        .put("assetId", assetId.toString())
                        .put("fileId", outputFileId.toString())
                        .put("version", outputVersion));
            }
            shot.put("status", StoryboardShotStatus.VIDEO_CANDIDATES.name());
        });
    }

    private void updateShot(
            UUID userId,
            UUID conversationId,
            UUID fileId,
            String shotId,
            ShotMutation mutation) {
        for (int attempt = 0; attempt <= WRITE_RETRIES; attempt++) {
            ConversationFile file = requireStoryboard(userId, conversationId, fileId);
            ConversationFileVersion version = fileService.readVersion(
                    userId, conversationId, fileId, null);
            ObjectNode root = readObject(version.getContent());
            ObjectNode shot = findShot(root, shotId)
                    .orElseThrow(() -> new BusinessException(
                            AgentErrorCode.STORYBOARD_SHOT_NOT_FOUND));
            mutation.apply(shot);
            validator.validate(objectMapper.writeValueAsString(root));
            try {
                fileService.update(
                        userId,
                        conversationId,
                        fileId,
                        new UpdateConversationFileCommand(
                                version.getVersion(),
                                objectMapper.writeValueAsString(root),
                                null,
                                ConversationFileSource.GENERATED));
                return;
            } catch (BusinessException exception) {
                if (exception.getErrorCode() != AssetErrorCode.FILE_VERSION_CONFLICT
                        || attempt == WRITE_RETRIES) {
                    throw exception;
                }
            }
        }
    }

    private ConversationFile requireStoryboard(
            UUID userId, UUID conversationId, UUID fileId) {
        ConversationFile file = fileService.get(userId, conversationId, fileId);
        if (file.getKind() != ConversationFileKind.STORYBOARD) {
            throw new BusinessException(AgentErrorCode.STORYBOARD_INVALID, "文件不是分镜");
        }
        return file;
    }

    private ObjectNode readObject(String content) {
        JsonNode node;
        try {
            node = objectMapper.readTree(content);
        } catch (RuntimeException exception) {
            throw new BusinessException(AgentErrorCode.STORYBOARD_INVALID, "分镜 JSON 无法解析");
        }
        if (!(node instanceof ObjectNode object)) {
            throw new BusinessException(AgentErrorCode.STORYBOARD_INVALID, "分镜必须是 JSON 对象");
        }
        return object;
    }

    private java.util.Optional<ObjectNode> findShot(ObjectNode root, String shotId) {
        JsonNode shots = root.path("shots");
        if (!shots.isArray()) {
            return java.util.Optional.empty();
        }
        for (JsonNode shot : shots) {
            if (shot instanceof ObjectNode object
                    && shotId.equals(object.path("id").asText())) {
                return java.util.Optional.of(object);
            }
        }
        return java.util.Optional.empty();
    }

    private ArrayNode array(ObjectNode shot, String field) {
        JsonNode existing = shot.path(field);
        if (existing instanceof ArrayNode array) {
            return array;
        }
        ArrayNode created = objectMapper.createArrayNode();
        shot.set(field, created);
        return created;
    }

    @FunctionalInterface
    private interface ShotMutation {
        void apply(ObjectNode shot);
    }
}
