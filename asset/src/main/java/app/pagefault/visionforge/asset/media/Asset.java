package app.pagefault.visionforge.asset.media;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import app.pagefault.visionforge.asset.storage.StorageBackend;

import lombok.Getter;
import lombok.Setter;

/**
 * A binary object owned by one user and one conversation. The object key is allocated by the
 * server; the database row is the permanent identity, never the temporary URL.
 */
@Getter
@Setter
@TableName("asset")
public class Asset {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private UUID conversationId;

    private AssetKind kind;

    private StorageBackend storageBackend;

    private String bucket;

    private String objectKey;

    private String mimeType;

    private long sizeBytes;

    private String checksum;

    private Integer width;

    private Integer height;

    private Long durationMs;

    private AssetSource source;

    private UUID generationTaskId;

    private AssetStatus status;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant deletedAt;
}
