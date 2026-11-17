package app.pagefault.visionforge.identity.workspace;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("workspace_member")
public class WorkspaceMember {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID workspaceId;

    private UUID userId;

    private WorkspaceRole role;

    private WorkspaceMemberStatus status;

    private Instant joinedAt;

    private Instant updatedAt;
}
