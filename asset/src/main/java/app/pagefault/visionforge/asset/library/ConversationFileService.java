package app.pagefault.visionforge.asset.library;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;

/**
 * Stores text files in a conversation's own work area and keeps their immutable versions.
 *
 * <p>Every operation is scoped by the requesting user and the conversation: the checker verifies
 * the conversation belongs to that user, and file lookups additionally match the conversation id
 * and owner. A file is never visible outside its conversation.
 */
@RequiredArgsConstructor
public class ConversationFileService {

    private static final int TITLE_MAX_LENGTH = 200;
    private static final int PATH_MAX_LENGTH = 500;
    private static final int CONTENT_MAX_BYTES = 1_048_576;
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;
    private static final String DEFAULT_MIME_TYPE = "text/markdown";

    private final ConversationFileMapper fileMapper;
    private final ConversationFileVersionMapper versionMapper;
    private final ConversationReferenceChecker conversationReferenceChecker;
    private final IdGenerator idGenerator;
    private final Clock clock;

    @Transactional
    public ConversationFile create(
            UUID userId, UUID conversationId, CreateConversationFileCommand command) {
        requireConversation(userId, conversationId);
        Objects.requireNonNull(command, "command");
        if (command.kind() == null) {
            throw new BusinessException(AssetErrorCode.FILE_KIND_REQUIRED);
        }
        if (!command.kind().isText()) {
            throw new BusinessException(AssetErrorCode.FILE_KIND_UNSUPPORTED);
        }
        String title = requireTitle(command.title());
        String path = normalizePath(command.path());
        String content = requireContent(command.content());
        requirePathAvailable(conversationId, path);

        Instant now = clock.instant();
        ConversationFile file = new ConversationFile();
        file.setId(idGenerator.nextId());
        file.setConversationId(conversationId);
        file.setUserId(userId);
        file.setKind(command.kind());
        file.setTitle(title);
        file.setPath(path);
        file.setMimeType(DEFAULT_MIME_TYPE);
        file.setStorageKind(ConversationFileStorageKind.DB_TEXT);
        file.setCurrentVersion(1);
        file.setStatus(ConversationFileStatus.DRAFT);
        file.setCreatedAt(now);
        file.setUpdatedAt(now);
        try {
            fileMapper.insert(file);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    AssetErrorCode.FILE_PATH_CONFLICT,
                    AssetErrorCode.FILE_PATH_CONFLICT.message(),
                    exception);
        }

        versionMapper.insert(newVersion(
                file.getId(), 1, content, command.source(), userId, null, now));
        return file;
    }

    /**
     * Publishes an archived media asset as a new conversation file version. Generated output is
     * always a new file; replacing an existing work requires a separate explicit operation.
     */
    @Transactional
    public PublishedMediaFile createMediaFile(
            UUID userId,
            UUID conversationId,
            ConversationFileKind kind,
            String title,
            UUID assetId,
            String mimeType,
            long sizeBytes,
            String checksum) {
        requireConversation(userId, conversationId);
        if (kind != ConversationFileKind.IMAGE && kind != ConversationFileKind.VIDEO) {
            throw new BusinessException(AssetErrorCode.FILE_KIND_UNSUPPORTED);
        }
        if (assetId == null) {
            throw new BusinessException(AssetErrorCode.ASSET_NOT_FOUND);
        }
        String safeTitle = requireTitle(title);
        Instant now = clock.instant();

        ConversationFile file = new ConversationFile();
        file.setId(idGenerator.nextId());
        file.setConversationId(conversationId);
        file.setUserId(userId);
        file.setKind(kind);
        file.setTitle(safeTitle);
        file.setPath(null);
        file.setMimeType(mimeType);
        file.setStorageKind(ConversationFileStorageKind.OBJECT_STORAGE);
        file.setCurrentVersion(1);
        file.setStatus(ConversationFileStatus.READY);
        file.setCreatedAt(now);
        file.setUpdatedAt(now);
        fileMapper.insert(file);

        ConversationFileVersion version = new ConversationFileVersion();
        version.setId(idGenerator.nextId());
        version.setFileId(file.getId());
        version.setVersion(1);
        version.setAssetId(assetId);
        version.setSizeBytes(sizeBytes);
        version.setChecksum(checksum);
        version.setSource(ConversationFileSource.GENERATED);
        version.setCreatedBy(userId);
        version.setCreatedAt(now);
        versionMapper.insert(version);
        return new PublishedMediaFile(file, version);
    }

    public ConversationFile get(UUID userId, UUID conversationId, UUID fileId) {
        requireConversation(userId, conversationId);
        return requireFile(userId, conversationId, fileId);
    }

    public void requireVersionInConversation(
            UUID userId, UUID conversationId, UUID versionId, UUID expectedAssetId) {
        requireConversation(userId, conversationId);
        ConversationFileVersion version = versionId == null ? null : versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(AssetErrorCode.FILE_VERSION_NOT_FOUND);
        }
        requireFile(userId, conversationId, version.getFileId());
        if (expectedAssetId != null
                && version.getAssetId() != null
                && !expectedAssetId.equals(version.getAssetId())) {
            throw new BusinessException(
                    AssetErrorCode.FILE_VERSION_NOT_FOUND, "文件版本与目标资产不匹配");
        }
    }

    public List<ConversationFile> list(
            UUID userId, UUID conversationId, ConversationFileKind kind, int limit) {
        requireConversation(userId, conversationId);
        return fileMapper.selectList(
                Wrappers.<ConversationFile>lambdaQuery()
                        .eq(ConversationFile::getConversationId, conversationId)
                        .isNull(ConversationFile::getDeletedAt)
                        .eq(kind != null, ConversationFile::getKind, kind)
                        .orderByDesc(ConversationFile::getUpdatedAt)
                        .last("LIMIT " + safeLimit(limit)));
    }

    public List<ConversationFile> search(
            UUID userId, UUID conversationId, String query,
            ConversationFileKind kind, int limit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            return list(userId, conversationId, kind, limit);
        }
        requireConversation(userId, conversationId);
        return fileMapper.selectList(
                Wrappers.<ConversationFile>lambdaQuery()
                        .eq(ConversationFile::getConversationId, conversationId)
                        .isNull(ConversationFile::getDeletedAt)
                        .eq(kind != null, ConversationFile::getKind, kind)
                        .and(wrapper -> wrapper
                                .like(ConversationFile::getTitle, normalized)
                                .or()
                                .like(ConversationFile::getPath, normalized))
                        .orderByDesc(ConversationFile::getUpdatedAt)
                        .last("LIMIT " + safeLimit(limit)));
    }

    public ConversationFileVersion readVersion(
            UUID userId, UUID conversationId, UUID fileId, Integer version) {
        ConversationFile file = get(userId, conversationId, fileId);
        int requested = version == null ? file.getCurrentVersion() : version;
        if (requested < 1) {
            throw new BusinessException(AssetErrorCode.FILE_VERSION_NOT_FOUND);
        }
        ConversationFileVersion result = versionMapper.selectOne(
                Wrappers.<ConversationFileVersion>lambdaQuery()
                        .eq(ConversationFileVersion::getFileId, fileId)
                        .eq(ConversationFileVersion::getVersion, requested));
        if (result == null) {
            throw new BusinessException(AssetErrorCode.FILE_VERSION_NOT_FOUND);
        }
        return result;
    }

    public List<ConversationFileVersion> listVersions(
            UUID userId, UUID conversationId, UUID fileId, int limit) {
        get(userId, conversationId, fileId);
        return versionMapper.selectList(
                Wrappers.<ConversationFileVersion>lambdaQuery()
                        .eq(ConversationFileVersion::getFileId, fileId)
                        .orderByDesc(ConversationFileVersion::getVersion)
                        .last("LIMIT " + safeLimit(limit)));
    }

    @Transactional
    public ConversationFile update(
            UUID userId, UUID conversationId, UUID fileId, UpdateConversationFileCommand command) {
        Objects.requireNonNull(command, "command");
        ConversationFile file = get(userId, conversationId, fileId);
        Integer baseVersion = command.baseVersion();
        if (baseVersion == null || baseVersion < 1 || baseVersion != file.getCurrentVersion()) {
            throw new BusinessException(AssetErrorCode.FILE_VERSION_CONFLICT);
        }
        String content = requireContent(command.content());
        String title = command.title() == null ? file.getTitle() : requireTitle(command.title());
        int nextVersion = file.getCurrentVersion() + 1;
        Instant now = clock.instant();

        try {
            versionMapper.insert(newVersion(
                    fileId, nextVersion, content, command.source(), userId, null, now));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    AssetErrorCode.FILE_VERSION_CONFLICT,
                    AssetErrorCode.FILE_VERSION_CONFLICT.message(),
                    exception);
        }

        LambdaUpdateWrapper<ConversationFile> update = Wrappers.<ConversationFile>lambdaUpdate()
                .eq(ConversationFile::getId, fileId)
                .eq(ConversationFile::getConversationId, conversationId)
                .eq(ConversationFile::getUserId, userId)
                .eq(ConversationFile::getCurrentVersion, baseVersion)
                .set(ConversationFile::getCurrentVersion, nextVersion)
                .set(ConversationFile::getTitle, title)
                .set(ConversationFile::getUpdatedAt, now);
        if (fileMapper.update(null, update) == 0) {
            throw new BusinessException(AssetErrorCode.FILE_VERSION_CONFLICT);
        }
        return requireFile(userId, conversationId, fileId);
    }

    private ConversationFile requireFile(
            UUID userId, UUID conversationId, UUID fileId) {
        ConversationFile file = fileId == null ? null : fileMapper.selectById(fileId);
        if (file == null
                || file.getDeletedAt() != null
                || !conversationId.equals(file.getConversationId())
                || !userId.equals(file.getUserId())) {
            throw new BusinessException(AssetErrorCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private void requirePathAvailable(UUID conversationId, String path) {
        if (path == null) {
            return;
        }
        Long count = fileMapper.selectCount(
                Wrappers.<ConversationFile>lambdaQuery()
                        .eq(ConversationFile::getConversationId, conversationId)
                        .eq(ConversationFile::getPath, path)
                        .isNull(ConversationFile::getDeletedAt));
        if (count != null && count > 0) {
            throw new BusinessException(AssetErrorCode.FILE_PATH_CONFLICT);
        }
    }

    private ConversationFileVersion newVersion(
            UUID fileId, int version, String content,
            ConversationFileSource source, UUID userId, UUID toolInvocationId, Instant now) {
        ConversationFileVersion result = new ConversationFileVersion();
        result.setId(idGenerator.nextId());
        result.setFileId(fileId);
        result.setVersion(version);
        result.setContent(content);
        result.setSizeBytes((long) content.getBytes(StandardCharsets.UTF_8).length);
        result.setChecksum(sha256(content));
        result.setSource(source == null ? ConversationFileSource.USER_UPLOAD : source);
        result.setCreatedBy(userId);
        result.setToolInvocationId(toolInvocationId);
        result.setCreatedAt(now);
        return result;
    }

    private String requireTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            throw new BusinessException(AssetErrorCode.FILE_TITLE_INVALID);
        }
        if (value.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException(AssetErrorCode.FILE_TITLE_TOO_LONG);
        }
        return value;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(AssetErrorCode.FILE_CONTENT_EMPTY);
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > CONTENT_MAX_BYTES) {
            throw new BusinessException(AssetErrorCode.FILE_CONTENT_TOO_LONG);
        }
        return content;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String value = path.trim().replace('\\', '/');
        if (value.length() > PATH_MAX_LENGTH) {
            throw new BusinessException(AssetErrorCode.FILE_PATH_INVALID);
        }
        if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new BusinessException(AssetErrorCode.FILE_PATH_INVALID);
        }
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new BusinessException(AssetErrorCode.FILE_PATH_INVALID);
            }
        }
        return value;
    }

    private void requireConversation(UUID userId, UUID conversationId) {
        if (userId == null || conversationId == null) {
            throw new BusinessException(AssetErrorCode.CONVERSATION_NOT_FOUND);
        }
        conversationReferenceChecker.requireOwned(userId, conversationId);
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
