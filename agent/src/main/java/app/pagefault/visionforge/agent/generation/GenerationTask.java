package app.pagefault.visionforge.agent.generation;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import app.pagefault.visionforge.agent.model.ExecutionMode;
import app.pagefault.visionforge.agent.model.MediaProtocol;

import lombok.Getter;
import lombok.Setter;

/**
 * One paid media generation request. It is independent of the AgentRun that accepted it and keeps
 * its own execution, delivery, cancel and billing state.
 */
@Getter
@Setter
@TableName("generation_task")
public class GenerationTask {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private UUID conversationId;

    private UUID agentRunId;

    private UUID toolInvocationId;

    private GenerationType type;

    private UUID modelDefinitionId;

    private MediaProtocol protocol;

    private ExecutionMode executionMode;

    private String prompt;

    private String parameters;

    private GenerationStatus status;

    private String providerStatus;

    private GenerationDeliveryStatus deliveryStatus;

    private GenerationBillingStatus billingStatus;

    private GenerationCancelStatus cancelStatus;

    private long priceUnits;

    private long budgetUnits;

    private String idempotencyKey;

    private String requestFingerprint;

    private UUID sourceTaskId;

    private String workflowId;

    private UUID outputFileId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    private int stateVersion;

    private Instant cancelRequestedAt;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant startedAt;

    private Instant completedAt;
}
