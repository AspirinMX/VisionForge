package app.pagefault.visionforge.api.generation.dto;

import java.util.UUID;

import app.pagefault.visionforge.agent.generation.GenerationInput;
import app.pagefault.visionforge.agent.generation.GenerationInputRole;

public record GenerationInputResponse(
        UUID assetId,
        UUID fileVersionId,
        GenerationInputRole role,
        int ordinal) {

    public static GenerationInputResponse from(GenerationInput input) {
        return new GenerationInputResponse(
                input.getAssetId(),
                input.getFileVersionId(),
                input.getRole(),
                input.getOrdinal());
    }
}
