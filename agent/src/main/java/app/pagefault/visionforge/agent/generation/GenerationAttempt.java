package app.pagefault.visionforge.agent.generation;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("generation_attempt")
public class GenerationAttempt {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID taskId;

    private int attemptNo;

    private String providerModelId;

    private String requestId;

    private String remoteTaskId;

    private GenerationAttemptStatus status;

    private String usagePayload;

    private String errorMessage;

    private Instant submittedAt;

    private Instant completedAt;

    private Instant createdAt;
}
