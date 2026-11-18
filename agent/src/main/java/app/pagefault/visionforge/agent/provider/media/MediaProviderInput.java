package app.pagefault.visionforge.agent.provider.media;

import java.util.UUID;

import app.pagefault.visionforge.agent.generation.GenerationInputRole;

/**
 * Frozen input reference handed to a provider adapter. The adapter resolves the asset through the
 * asset service; the URL field is only a pre-signed hint and may be null for adapters that read
 * bytes directly.
 */
public record MediaProviderInput(
        UUID assetId,
        GenerationInputRole role,
        String mimeType,
        String url,
        byte[] content) {
}
