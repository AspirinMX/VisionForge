package app.pagefault.visionforge.api.generation.dto;

import java.util.UUID;

import app.pagefault.visionforge.agent.generation.GenerationOutput;
import app.pagefault.visionforge.agent.generation.GenerationOutputStatus;

public record GenerationOutputResponse(
        int outputIndex,
        GenerationOutputStatus status,
        UUID assetId,
        UUID fileId,
        UUID fileVersionId,
        String errorMessage) {

    public static GenerationOutputResponse from(GenerationOutput output) {
        return new GenerationOutputResponse(
                output.getOutputIndex(),
                output.getStatus(),
                output.getAssetId(),
                output.getFileId(),
                output.getFileVersionId(),
                output.getErrorMessage());
    }
}
