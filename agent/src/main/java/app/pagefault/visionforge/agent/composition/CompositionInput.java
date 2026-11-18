package app.pagefault.visionforge.agent.composition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("composition_input")
public class CompositionInput {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID taskId;

    private String shotId;

    private UUID generationTaskId;

    private UUID assetId;

    private UUID fileVersionId;

    private BigDecimal startSeconds;

    private BigDecimal endSeconds;

    private int ordinal;

    private Instant createdAt;
}
