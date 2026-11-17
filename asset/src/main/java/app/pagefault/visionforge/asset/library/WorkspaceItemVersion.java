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
@TableName("workspace_item_version")
public class WorkspaceItemVersion {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID itemId;

    private int version;

    private String content;

    private UUID assetId;

    private String objectKey;

    private Long sizeBytes;

    private String checksum;

    private WorkspaceItemSource source;

    private UUID createdBy;

    private UUID toolInvocationId;

    private Instant createdAt;
}
