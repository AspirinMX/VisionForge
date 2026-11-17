package app.pagefault.visionforge.asset.upload;

import app.pagefault.visionforge.asset.media.AssetKind;

public record CreateUploadCommand(
        AssetKind kind,
        String filename,
        String mimeType,
        long sizeBytes) {
}
