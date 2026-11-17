package app.pagefault.visionforge.agent.runtime;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * One model turn of an AgentRun. Persisting the turn keeps the workflow payload small and lets a
 * replayed activity rebuild the exact Responses {@code input} without re-asking the model.
 */
@Getter
@Setter
@TableName("agent_run_turn")
public class AgentRunTurn {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID agentRunId;

    private int turnNo;

    private String assistantText;

    /** JSON array of {@code {"callId","name","arguments"}}. */
    private String functionCalls;

    private String responseId;

    private AgentTurnStatus status;

    private Instant createdAt;
}
