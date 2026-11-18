package app.pagefault.visionforge.agent.composition;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("composition_task")
public class CompositionTask {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private UUID conversationId;

    private UUID storyboardFileId;

    private int storyboardVersion;

    private String outputSpec;

    private CompositionStatus status;

    private String workflowId;

    private String multipartUploadId;

    private UUID outputAssetId;

    private UUID outputFileId;

    private UUID outputVersionId;

    private String errorMessage;

    private int stateVersion;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant startedAt;

    private Instant completedAt;
}
