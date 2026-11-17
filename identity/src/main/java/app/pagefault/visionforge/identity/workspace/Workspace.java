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
@TableName("workspace")
public class Workspace {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String name;

    private String description;

    private WorkspaceStatus status;

    private boolean personal;

    private Instant createdAt;

    private Instant updatedAt;
}
