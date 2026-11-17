package app.pagefault.visionforge.agent.context;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Structured summary of a message range. It never replaces the original messages; later inputs
 * submit the summary instead of the covered range.
 */
@Getter
@Setter
@TableName("context_summary")
public class ContextSummary {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private UUID conversationId;

    private int epoch;

    private long fromSeq;

    private long toSeq;

    private String content;

    /** Digest of the covered messages, used to detect a stale summary. */
    private String sourceDigest;

    private UUID modelDefinitionId;

    private String promptVersion;

    private Integer inputTokens;

    private Integer outputTokens;

    private Instant createdAt;
}
