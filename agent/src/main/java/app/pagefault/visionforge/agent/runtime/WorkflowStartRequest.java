package app.pagefault.visionforge.agent.runtime;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("workflow_start_request")
public class WorkflowStartRequest {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID agentRunId;

    private WorkflowStartType workflowType;

    private UUID businessId;

    private int inputSchemaVersion;

    private String workflowId;

    private WorkflowStartStatus status;

    private int attempts;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private Instant createdAt;

    private Instant updatedAt;
}
