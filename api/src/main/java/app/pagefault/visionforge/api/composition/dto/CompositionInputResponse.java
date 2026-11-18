package app.pagefault.visionforge.api.composition.dto;

import java.math.BigDecimal;
import java.util.UUID;

import app.pagefault.visionforge.agent.composition.CompositionInput;

public record CompositionInputResponse(
        String shotId,
        UUID generationTaskId,
        UUID assetId,
        BigDecimal startSeconds,
        BigDecimal endSeconds,
        int ordinal) {

    public static CompositionInputResponse from(CompositionInput input) {
        return new CompositionInputResponse(
                input.getShotId(),
                input.getGenerationTaskId(),
                input.getAssetId(),
                input.getStartSeconds(),
                input.getEndSeconds(),
                input.getOrdinal());
    }
}
