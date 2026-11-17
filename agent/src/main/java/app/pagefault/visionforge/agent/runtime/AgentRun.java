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
@TableName("agent_run")
public class AgentRun {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID workspaceId;

    private UUID conversationId;

    private UUID userMessageId;

    private UUID assistantMessageId;

    private UUID modelDefinitionId;

    private AgentRunStatus status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    private String workflowId;

    private Instant createdAt;

    private Instant startedAt;

    private Instant completedAt;
}
