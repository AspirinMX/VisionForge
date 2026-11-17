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
@TableName("generation_output")
public class GenerationOutput {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID taskId;

    private UUID attemptId;

    private int outputIndex;

    private UUID assetId;

    private UUID fileId;

    private UUID fileVersionId;

    private GenerationOutputStatus status;

    private String errorMessage;

    private Instant createdAt;

    private Instant updatedAt;
}
