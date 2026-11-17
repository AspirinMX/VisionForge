package app.pagefault.visionforge.api.generation.dto;

import java.util.UUID;

import app.pagefault.visionforge.agent.generation.GenerationInputRole;

import jakarta.validation.constraints.NotNull;

public record GenerationInputRequest(
        @NotNull(message = "素材 ID 不能为空")
        UUID assetId,
        UUID fileVersionId,
        GenerationInputRole role) {
}
