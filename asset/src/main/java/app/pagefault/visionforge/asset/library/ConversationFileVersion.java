package app.pagefault.visionforge.asset.library;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Immutable content version of a {@link ConversationFile}.
 */
@Getter
@Setter
@TableName("conversation_file_version")
public class ConversationFileVersion {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID fileId;

    private int version;

    private String content;

    private UUID assetId;

    private String objectKey;

    private Long sizeBytes;

    private String checksum;

    private ConversationFileSource source;

    private UUID createdBy;

    private UUID toolInvocationId;

    private Instant createdAt;
}
