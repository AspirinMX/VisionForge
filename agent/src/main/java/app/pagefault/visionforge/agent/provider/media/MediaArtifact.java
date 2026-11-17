package app.pagefault.visionforge.agent.provider.media;

/**
 * One produced binary. The workflow hands this to the archive activity immediately; it is never
 * serialized into Temporal history.
 */
public record MediaArtifact(int index, String mimeType, byte[] content) {
}
