package app.pagefault.visionforge.asset.library;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;

/**
 * Stores text documents in the workspace library and links them to conversations.
 *
 * <p>An item belongs to the workspace, never to a conversation. Conversation links are metadata;
 * the item and its versions remain available after a conversation is removed.
 */
@RequiredArgsConstructor
public class WorkspaceItemService {

    private static final int TITLE_MAX_LENGTH = 200;
    private static final int PATH_MAX_LENGTH = 500;
    private static final int CONTENT_MAX_BYTES = 1_048_576;
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;
    private static final String DEFAULT_MIME_TYPE = "text/markdown";

    private final WorkspaceItemMapper itemMapper;
    private final WorkspaceItemVersionMapper versionMapper;
    private final ConversationItemMapper conversationItemMapper;
    private final ConversationReferenceChecker conversationReferenceChecker;
    private final IdGenerator idGenerator;
    private final Clock clock;

    @Transactional
    public WorkspaceItem create(UUID workspaceId, UUID userId, CreateWorkspaceItemCommand command) {
        requireIdentity(workspaceId, userId);
        Objects.requireNonNull(command, "command");
        if (command.kind() == null) {
            throw new BusinessException(AssetErrorCode.ITEM_KIND_REQUIRED);
        }
        if (!command.kind().isText()) {
            throw new BusinessException(AssetErrorCode.ITEM_KIND_UNSUPPORTED);
        }
        String title = requireTitle(command.title());
        String path = normalizePath(command.path());
        String content = requireContent(command.content());
        WorkspaceItemVisibility visibility = command.visibility() == null
                ? WorkspaceItemVisibility.PRIVATE
                : command.visibility();
        ConversationItemRole role = command.role() == null
                ? ConversationItemRole.WORKING
                : command.role();

        if (command.conversationId() != null) {
            requireConversation(workspaceId, command.conversationId());
        }

        Instant now = clock.instant();
        WorkspaceItem item = new WorkspaceItem();
        item.setId(idGenerator.nextId());
        item.setWorkspaceId(workspaceId);
        item.setKind(command.kind());
        item.setTitle(title);
        item.setPath(path);
        item.setMimeType(DEFAULT_MIME_TYPE);
        item.setStorageKind(WorkspaceItemStorageKind.DB_TEXT);
        item.setCurrentVersion(1);
        item.setStatus(WorkspaceItemStatus.DRAFT);
        item.setVisibility(visibility);
        item.setCreatedBy(userId);
        item.setOriginConversationId(command.conversationId());
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        requirePathAvailable(workspaceId, path);
        try {
            itemMapper.insert(item);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    AssetErrorCode.ITEM_PATH_CONFLICT,
                    AssetErrorCode.ITEM_PATH_CONFLICT.message(),
                    exception);
        }

        WorkspaceItemVersion version = newVersion(
                item.getId(), 1, content, command.source(), userId, null, now);
        versionMapper.insert(version);

        if (command.conversationId() != null) {
            insertBinding(workspaceId, command.conversationId(), item.getId(), role, null, userId, now);
        }
        return item;
    }

    public WorkspaceItem get(UUID workspaceId, UUID userId, boolean platformAdmin, UUID itemId) {
        return requireVisibleItem(workspaceId, userId, platformAdmin, itemId);
    }

    public List<WorkspaceItem> list(
            UUID workspaceId, UUID userId, boolean platformAdmin,
            WorkspaceItemKind kind, int limit) {
        requireIdentity(workspaceId, userId);
        return itemMapper.selectList(
                visibleQuery(workspaceId, userId, platformAdmin)
                        .eq(kind != null, WorkspaceItem::getKind, kind)
                        .orderByDesc(WorkspaceItem::getUpdatedAt)
                        .last("LIMIT " + safeLimit(limit)));
    }

    public List<WorkspaceItem> search(
            UUID workspaceId, UUID userId, boolean platformAdmin,
            String query, WorkspaceItemKind kind, int limit) {
        requireIdentity(workspaceId, userId);
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            return list(workspaceId, userId, platformAdmin, kind, limit);
        }
        return itemMapper.selectList(
                visibleQuery(workspaceId, userId, platformAdmin)
                        .eq(kind != null, WorkspaceItem::getKind, kind)
                        .and(wrapper -> wrapper
                                .like(WorkspaceItem::getTitle, normalized)
                                .or()
                                .like(WorkspaceItem::getPath, normalized))
                        .orderByDesc(WorkspaceItem::getUpdatedAt)
                        .last("LIMIT " + safeLimit(limit)));
    }

    public WorkspaceItemVersion readVersion(
            UUID workspaceId, UUID userId, boolean platformAdmin, UUID itemId, Integer version) {
        WorkspaceItem item = requireVisibleItem(workspaceId, userId, platformAdmin, itemId);
        int requested = version == null ? item.getCurrentVersion() : version;
        if (requested < 1) {
            throw new BusinessException(AssetErrorCode.ITEM_VERSION_NOT_FOUND);
        }
        WorkspaceItemVersion result = versionMapper.selectOne(
                Wrappers.<WorkspaceItemVersion>lambdaQuery()
                        .eq(WorkspaceItemVersion::getItemId, itemId)
                        .eq(WorkspaceItemVersion::getVersion, requested));
        if (result == null) {
            throw new BusinessException(AssetErrorCode.ITEM_VERSION_NOT_FOUND);
        }
        return result;
    }

    public List<WorkspaceItemVersion> listVersions(
            UUID workspaceId, UUID userId, boolean platformAdmin, UUID itemId, int limit) {
        requireVisibleItem(workspaceId, userId, platformAdmin, itemId);
        return versionMapper.selectList(
                Wrappers.<WorkspaceItemVersion>lambdaQuery()
                        .eq(WorkspaceItemVersion::getItemId, itemId)
                        .orderByDesc(WorkspaceItemVersion::getVersion)
                        .last("LIMIT " + safeLimit(limit)));
    }

    @Transactional
    public WorkspaceItem update(
            UUID workspaceId, UUID userId, boolean platformAdmin,
            UUID itemId, UpdateWorkspaceItemCommand command) {
        Objects.requireNonNull(command, "command");
        WorkspaceItem item = requireVisibleItem(workspaceId, userId, platformAdmin, itemId);
        Integer baseVersion = command.baseVersion();
        if (baseVersion == null || baseVersion < 1) {
            throw new BusinessException(AssetErrorCode.ITEM_VERSION_CONFLICT);
        }
        if (baseVersion != item.getCurrentVersion()) {
            throw new BusinessException(AssetErrorCode.ITEM_VERSION_CONFLICT);
        }
        String content = requireContent(command.content());
        String title = command.title() == null ? item.getTitle() : requireTitle(command.title());
        int nextVersion = item.getCurrentVersion() + 1;
        Instant now = clock.instant();

        WorkspaceItemVersion version = newVersion(
                itemId, nextVersion, content, command.source(), userId, null, now);
        try {
            versionMapper.insert(version);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    AssetErrorCode.ITEM_VERSION_CONFLICT,
                    AssetErrorCode.ITEM_VERSION_CONFLICT.message(),
                    exception);
        }

        LambdaUpdateWrapper<WorkspaceItem> update = Wrappers.<WorkspaceItem>lambdaUpdate()
                .eq(WorkspaceItem::getId, itemId)
                .eq(WorkspaceItem::getWorkspaceId, workspaceId)
                .eq(WorkspaceItem::getCurrentVersion, baseVersion)
                .set(WorkspaceItem::getCurrentVersion, nextVersion)
                .set(WorkspaceItem::getTitle, title)
                .set(WorkspaceItem::getUpdatedAt, now);
        if (itemMapper.update(null, update) == 0) {
            throw new BusinessException(AssetErrorCode.ITEM_VERSION_CONFLICT);
        }
        return requireItem(workspaceId, itemId);
    }

    @Transactional
    public ConversationItem bind(
            UUID workspaceId, UUID userId, boolean platformAdmin, UUID conversationId,
            UUID itemId, ConversationItemRole role, Integer pinnedVersion) {
        requireIdentity(workspaceId, userId);
        requireConversation(workspaceId, conversationId);
        WorkspaceItem item = requireVisibleItem(workspaceId, userId, platformAdmin, itemId);
        ConversationItemRole resolvedRole = role == null ? ConversationItemRole.WORKING : role;
        if (pinnedVersion != null) {
            requireVersion(item.getId(), pinnedVersion);
        }

        ConversationItem existing = conversationItemMapper.selectOne(
                Wrappers.<ConversationItem>lambdaQuery()
                        .eq(ConversationItem::getConversationId, conversationId)
                        .eq(ConversationItem::getItemId, itemId));
        if (existing != null) {
            existing.setRole(resolvedRole);
            existing.setPinnedVersion(pinnedVersion);
            existing.setAddedBy(userId);
            conversationItemMapper.updateById(existing);
            return existing;
        }
        return insertBinding(
                workspaceId, conversationId, itemId, resolvedRole, pinnedVersion, userId,
                clock.instant());
    }

    @Transactional
    public void unbind(
            UUID workspaceId, UUID userId, boolean platformAdmin,
            UUID conversationId, UUID itemId) {
        requireConversation(workspaceId, conversationId);
        requireVisibleItem(workspaceId, userId, platformAdmin, itemId);
        int removed = conversationItemMapper.delete(
                Wrappers.<ConversationItem>lambdaQuery()
                        .eq(ConversationItem::getWorkspaceId, workspaceId)
                        .eq(ConversationItem::getConversationId, conversationId)
                        .eq(ConversationItem::getItemId, itemId));
        if (removed == 0) {
            throw new BusinessException(AssetErrorCode.CONVERSATION_ITEM_NOT_FOUND);
        }
    }

    public List<ConversationItemView> listConversationItems(
            UUID workspaceId, UUID userId, boolean platformAdmin, UUID conversationId, int limit) {
        requireIdentity(workspaceId, userId);
        requireConversation(workspaceId, conversationId);
        List<ConversationItem> bindings = conversationItemMapper.selectList(
                Wrappers.<ConversationItem>lambdaQuery()
                        .eq(ConversationItem::getWorkspaceId, workspaceId)
                        .eq(ConversationItem::getConversationId, conversationId)
                        .orderByAsc(ConversationItem::getAddedAt)
                        .last("LIMIT " + safeLimit(limit)));
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<UUID, WorkspaceItem> items = itemMapper
                .selectBatchIds(bindings.stream().map(ConversationItem::getItemId).toList())
                .stream()
                .filter(item -> item.getDeletedAt() == null)
                .filter(item -> isVisible(item, userId, platformAdmin))
                .collect(Collectors.toMap(WorkspaceItem::getId, Function.identity()));
        List<ConversationItemView> views = new ArrayList<>();
        for (ConversationItem binding : bindings) {
            WorkspaceItem item = items.get(binding.getItemId());
            if (item != null) {
                views.add(new ConversationItemView(binding, item));
            }
        }
        return views;
    }

    private WorkspaceItem requireVisibleItem(
            UUID workspaceId, UUID userId, boolean platformAdmin, UUID itemId) {
        requireIdentity(workspaceId, userId);
        WorkspaceItem item = requireItem(workspaceId, itemId);
        if (!isVisible(item, userId, platformAdmin)) {
            throw new BusinessException(AssetErrorCode.ITEM_NOT_FOUND);
        }
        return item;
    }

    private WorkspaceItem requireItem(UUID workspaceId, UUID itemId) {
        WorkspaceItem item = itemId == null ? null : itemMapper.selectById(itemId);
        if (item == null
                || item.getDeletedAt() != null
                || !workspaceId.equals(item.getWorkspaceId())) {
            throw new BusinessException(AssetErrorCode.ITEM_NOT_FOUND);
        }
        return item;
    }

    private boolean isVisible(WorkspaceItem item, UUID userId, boolean platformAdmin) {
        return platformAdmin
                || item.getVisibility() == WorkspaceItemVisibility.WORKSPACE
                || userId.equals(item.getCreatedBy());
    }

    private LambdaQueryWrapper<WorkspaceItem> visibleQuery(
            UUID workspaceId, UUID userId, boolean platformAdmin) {
        LambdaQueryWrapper<WorkspaceItem> query = Wrappers.<WorkspaceItem>lambdaQuery()
                .eq(WorkspaceItem::getWorkspaceId, workspaceId)
                .isNull(WorkspaceItem::getDeletedAt);
        if (!platformAdmin) {
            query.and(wrapper -> wrapper
                    .eq(WorkspaceItem::getCreatedBy, userId)
                    .or()
                    .eq(WorkspaceItem::getVisibility, WorkspaceItemVisibility.WORKSPACE));
        }
        return query;
    }

    private void requirePathAvailable(UUID workspaceId, String path) {
        if (path == null) {
            return;
        }
        Long count = itemMapper.selectCount(
                Wrappers.<WorkspaceItem>lambdaQuery()
                        .eq(WorkspaceItem::getWorkspaceId, workspaceId)
                        .eq(WorkspaceItem::getPath, path)
                        .isNull(WorkspaceItem::getDeletedAt));
        if (count != null && count > 0) {
            throw new BusinessException(AssetErrorCode.ITEM_PATH_CONFLICT);
        }
    }

    private void requireVersion(UUID itemId, int version) {
        Long count = versionMapper.selectCount(
                Wrappers.<WorkspaceItemVersion>lambdaQuery()
                        .eq(WorkspaceItemVersion::getItemId, itemId)
                        .eq(WorkspaceItemVersion::getVersion, version));
        if (count == null || count == 0) {
            throw new BusinessException(AssetErrorCode.ITEM_VERSION_NOT_FOUND);
        }
    }

    private ConversationItem insertBinding(
            UUID workspaceId, UUID conversationId, UUID itemId,
            ConversationItemRole role, Integer pinnedVersion, UUID userId, Instant now) {
        ConversationItem binding = new ConversationItem();
        binding.setId(idGenerator.nextId());
        binding.setWorkspaceId(workspaceId);
        binding.setConversationId(conversationId);
        binding.setItemId(itemId);
        binding.setRole(role);
        binding.setPinnedVersion(pinnedVersion);
        binding.setAddedBy(userId);
        binding.setAddedAt(now);
        conversationItemMapper.insert(binding);
        return binding;
    }

    private WorkspaceItemVersion newVersion(
            UUID itemId, int version, String content,
            WorkspaceItemSource source, UUID userId, UUID toolInvocationId, Instant now) {
        WorkspaceItemVersion result = new WorkspaceItemVersion();
        result.setId(idGenerator.nextId());
        result.setItemId(itemId);
        result.setVersion(version);
        result.setContent(content);
        result.setSizeBytes((long) content.getBytes(StandardCharsets.UTF_8).length);
        result.setChecksum(sha256(content));
        result.setSource(source == null ? WorkspaceItemSource.USER_UPLOAD : source);
        result.setCreatedBy(userId);
        result.setToolInvocationId(toolInvocationId);
        result.setCreatedAt(now);
        return result;
    }

    private String requireTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            throw new BusinessException(AssetErrorCode.ITEM_TITLE_INVALID);
        }
        if (value.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException(AssetErrorCode.ITEM_TITLE_TOO_LONG);
        }
        return value;
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(AssetErrorCode.ITEM_CONTENT_EMPTY);
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > CONTENT_MAX_BYTES) {
            throw new BusinessException(AssetErrorCode.ITEM_CONTENT_TOO_LONG);
        }
        return content;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String value = path.trim().replace('\\', '/');
        if (value.length() > PATH_MAX_LENGTH) {
            throw new BusinessException(AssetErrorCode.ITEM_PATH_INVALID);
        }
        if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new BusinessException(AssetErrorCode.ITEM_PATH_INVALID);
        }
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new BusinessException(AssetErrorCode.ITEM_PATH_INVALID);
            }
        }
        return value;
    }

    private void requireConversation(UUID workspaceId, UUID conversationId) {
        if (conversationId == null) {
            throw new BusinessException(AssetErrorCode.CONVERSATION_ITEM_NOT_FOUND);
        }
        conversationReferenceChecker.require(workspaceId, conversationId);
    }

    private void requireIdentity(UUID workspaceId, UUID userId) {
        if (workspaceId == null || userId == null) {
            throw new BusinessException(AssetErrorCode.ITEM_NOT_FOUND);
        }
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
