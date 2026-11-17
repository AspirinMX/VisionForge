package app.pagefault.visionforge.asset.upload;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import app.pagefault.visionforge.asset.media.AssetKind;

import lombok.Getter;
import lombok.Setter;

/**
 * A direct-to-storage upload. The client receives a presigned PUT, uploads the bytes, then asks the
 * server to verify the object before it becomes a READY asset.
 */
@Getter
@Setter
@TableName("upload_session")
public class UploadSession {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    private UUID conversationId;

    private UUID assetId;

    private AssetKind kind;

    private String bucket;

    private String objectKey;

    private String mimeType;

    private long expectedSize;

    private UploadSessionStatus status;

    private Instant expiresAt;

    private Instant createdAt;

    private Instant updatedAt;
}
