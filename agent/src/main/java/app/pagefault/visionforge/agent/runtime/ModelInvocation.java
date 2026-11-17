package app.pagefault.visionforge.agent.runtime;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("model_invocation")
public class ModelInvocation {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID agentRunId;

    private UUID modelDefinitionId;

    private ModelInvocationStatus status;

    private ModelInvocationPurpose purpose;

    private Integer inputTokens;

    private Integer outputTokens;

    private Long latencyMs;

    private String finishReason;

    private String responseId;

    private String errorMessage;

    private Instant createdAt;
}
