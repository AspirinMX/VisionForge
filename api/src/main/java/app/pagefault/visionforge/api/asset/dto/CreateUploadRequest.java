package app.pagefault.visionforge.api.asset.dto;

import app.pagefault.visionforge.asset.media.AssetKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateUploadRequest(
        @NotNull AssetKind kind,
        @NotBlank String filename,
        @NotBlank String mimeType,
        @Positive long sizeBytes) {
}
