package app.pagefault.visionforge.agent.generation;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * A frozen input asset of a generation task. The file version and asset are fixed at acceptance;
 * later edits to the source work area never change a submitted task.
 */
@Getter
@Setter
@TableName("generation_input")
public class GenerationInput {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID taskId;

    private UUID assetId;

    private UUID fileVersionId;

    private GenerationInputRole role;

    private int ordinal;

    private Instant createdAt;
}
