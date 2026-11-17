package app.pagefault.visionforge.asset.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import app.pagefault.visionforge.asset.error.AssetErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

class WorkspaceItemServiceTest {

    private static final UUID WORKSPACE_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e2f");
    private static final UUID USER_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID OTHER_USER_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e31");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e32");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private WorkspaceItemMapper itemMapper;
    private WorkspaceItemVersionMapper versionMapper;
    private ConversationItemMapper conversationItemMapper;
    private ConversationReferenceChecker conversationReferenceChecker;
    private WorkspaceItemService service;

    @BeforeEach
    void setUp() {
        initTableInfo(WorkspaceItem.class);
        initTableInfo(WorkspaceItemVersion.class);
        initTableInfo(ConversationItem.class);
        itemMapper = mock(WorkspaceItemMapper.class);
        versionMapper = mock(WorkspaceItemVersionMapper.class);
        conversationItemMapper = mock(ConversationItemMapper.class);
        conversationReferenceChecker = mock(ConversationReferenceChecker.class);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        service = new WorkspaceItemService(
                itemMapper,
                versionMapper,
                conversationItemMapper,
                conversationReferenceChecker,
                idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createStoresFirstVersionAndBindsConversation() {
        WorkspaceItem item = service.create(
                WORKSPACE_ID,
                USER_ID,
                new CreateWorkspaceItemCommand(
                        WorkspaceItemKind.SCRIPT,
                        "第一幕",
                        "scripts/act-1.md",
                        "INT. STUDIO - DAY",
                        null,
                        CONVERSATION_ID,
                        null,
                        WorkspaceItemSource.AGENT));

        assertThat(item.getCurrentVersion()).isEqualTo(1);
        assertThat(item.getStorageKind()).isEqualTo(WorkspaceItemStorageKind.DB_TEXT);
        assertThat(item.getVisibility()).isEqualTo(WorkspaceItemVisibility.PRIVATE);
        assertThat(item.getOriginConversationId()).isEqualTo(CONVERSATION_ID);

        ArgumentCaptor<WorkspaceItemVersion> versionCaptor =
                ArgumentCaptor.forClass(WorkspaceItemVersion.class);
        verify(versionMapper).insert(versionCaptor.capture());
        WorkspaceItemVersion version = versionCaptor.getValue();
        assertThat(version.getVersion()).isEqualTo(1);
        assertThat(version.getContent()).isEqualTo("INT. STUDIO - DAY");
        assertThat(version.getSource()).isEqualTo(WorkspaceItemSource.AGENT);
        assertThat(version.getChecksum()).isNotBlank();
        assertThat(version.getSizeBytes()).isPositive();

        ArgumentCaptor<ConversationItem> bindingCaptor =
                ArgumentCaptor.forClass(ConversationItem.class);
        verify(conversationItemMapper).insert(bindingCaptor.capture());
        assertThat(bindingCaptor.getValue().getRole()).isEqualTo(ConversationItemRole.WORKING);
        verify(conversationReferenceChecker).require(WORKSPACE_ID, CONVERSATION_ID);
    }

    @Test
    void createRejectsBinaryKindUntilAssetStorageExists() {
        assertThatThrownBy(() -> service.create(
                WORKSPACE_ID,
                USER_ID,
                new CreateWorkspaceItemCommand(
                        WorkspaceItemKind.IMAGE,
                        "参考图",
                        null,
                        "not-binary",
                        null,
                        null,
                        null,
                        WorkspaceItemSource.USER_UPLOAD)))
                .satisfies(exception -> assertError(
                        exception, AssetErrorCode.ITEM_KIND_UNSUPPORTED));
    }

    @Test
    void createRejectsPathTraversal() {
        assertThatThrownBy(() -> service.create(
                WORKSPACE_ID,
                USER_ID,
                new CreateWorkspaceItemCommand(
                        WorkspaceItemKind.SCRIPT,
                        "越权路径",
                        "../secret.md",
                        "content",
                        null,
                        null,
                        null,
                        WorkspaceItemSource.USER_UPLOAD)))
                .satisfies(exception -> assertError(
                        exception, AssetErrorCode.ITEM_PATH_INVALID));
    }

    @Test
    void createRejectsDuplicatePath() {
        when(itemMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(
                WORKSPACE_ID,
                USER_ID,
                new CreateWorkspaceItemCommand(
                        WorkspaceItemKind.SCRIPT,
                        "重复路径",
                        "scripts/act-1.md",
                        "content",
                        null,
                        null,
                        null,
                        WorkspaceItemSource.USER_UPLOAD)))
                .satisfies(exception -> assertError(
                        exception, AssetErrorCode.ITEM_PATH_CONFLICT));
    }

    @Test
    void getHidesPrivateItemFromOtherUser() {
        WorkspaceItem item = item(1);
        when(itemMapper.selectById(item.getId())).thenReturn(item);

        assertThatThrownBy(() -> service.get(
                WORKSPACE_ID, OTHER_USER_ID, false, item.getId()))
                .satisfies(exception -> assertError(
                        exception, AssetErrorCode.ITEM_NOT_FOUND));
    }

    @Test
    void updateCreatesNextVersionAndUsesBaseVersion() {
        WorkspaceItem existing = item(1);
        WorkspaceItem updated = item(2);
        when(itemMapper.selectById(existing.getId())).thenReturn(existing, updated);
        when(itemMapper.update(isNull(), any())).thenReturn(1);

        WorkspaceItem result = service.update(
                WORKSPACE_ID,
                USER_ID,
                false,
                existing.getId(),
                new UpdateWorkspaceItemCommand(1, "INT. STUDIO - NIGHT", null, WorkspaceItemSource.AGENT));

        assertThat(result.getCurrentVersion()).isEqualTo(2);
        ArgumentCaptor<WorkspaceItemVersion> versionCaptor =
                ArgumentCaptor.forClass(WorkspaceItemVersion.class);
        verify(versionMapper).insert(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getVersion()).isEqualTo(2);
        assertThat(versionCaptor.getValue().getContent()).isEqualTo("INT. STUDIO - NIGHT");
    }

    @Test
    void updateRejectsStaleBaseVersion() {
        WorkspaceItem existing = item(2);
        when(itemMapper.selectById(existing.getId())).thenReturn(existing);

        assertThatThrownBy(() -> service.update(
                WORKSPACE_ID,
                USER_ID,
                false,
                existing.getId(),
                new UpdateWorkspaceItemCommand(1, "stale", null, WorkspaceItemSource.AGENT)))
                .satisfies(exception -> assertError(
                        exception, AssetErrorCode.ITEM_VERSION_CONFLICT));

        verify(versionMapper, never()).insert(any(WorkspaceItemVersion.class));
    }

    @Test
    void bindRejectsMissingPinnedVersion() {
        WorkspaceItem item = item(1);
        when(itemMapper.selectById(item.getId())).thenReturn(item);
        when(versionMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.bind(
                WORKSPACE_ID,
                USER_ID,
                false,
                CONVERSATION_ID,
                item.getId(),
                ConversationItemRole.REFERENCE,
                99))
                .satisfies(exception -> assertError(
                        exception, AssetErrorCode.ITEM_VERSION_NOT_FOUND));
    }

    private WorkspaceItem item(int currentVersion) {
        WorkspaceItem item = new WorkspaceItem();
        item.setId(UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e40"));
        item.setWorkspaceId(WORKSPACE_ID);
        item.setKind(WorkspaceItemKind.SCRIPT);
        item.setTitle("第一幕");
        item.setPath("scripts/act-1.md");
        item.setMimeType("text/markdown");
        item.setStorageKind(WorkspaceItemStorageKind.DB_TEXT);
        item.setCurrentVersion(currentVersion);
        item.setStatus(WorkspaceItemStatus.DRAFT);
        item.setVisibility(WorkspaceItemVisibility.PRIVATE);
        item.setCreatedBy(USER_ID);
        item.setCreatedAt(NOW);
        item.setUpdatedAt(NOW);
        return item;
    }

    private void initTableInfo(Class<?> type) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }

    private void assertError(Throwable exception, AssetErrorCode errorCode) {
        assertThat(exception).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(errorCode);
    }
}
