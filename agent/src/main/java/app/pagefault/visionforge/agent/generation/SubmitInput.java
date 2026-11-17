package app.pagefault.visionforge.agent.generation;

import java.util.UUID;

public record SubmitInput(
        UUID assetId,
        UUID fileVersionId,
        GenerationInputRole role) {
}
