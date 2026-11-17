package app.pagefault.visionforge.asset.media;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * A derived rendition of an asset, for example a thumbnail, preview, cover or playback file.
 */
@Getter
@Setter
@TableName("asset_variant")
public class AssetVariant {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID assetId;

    private AssetVariantKind variant;

    private String bucket;

    private String objectKey;

    private String mimeType;

    private long sizeBytes;

    private Integer width;

    private Integer height;

    private Instant createdAt;
}
