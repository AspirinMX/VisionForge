package app.pagefault.visionforge.asset.library;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("workspace_item")
public class WorkspaceItem {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID workspaceId;

    private WorkspaceItemKind kind;

    private String title;

    private String path;

    private String mimeType;

    private WorkspaceItemStorageKind storageKind;

    private int currentVersion;

    private WorkspaceItemStatus status;

    private WorkspaceItemVisibility visibility;

    private UUID createdBy;

    private UUID originConversationId;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant deletedAt;
}
