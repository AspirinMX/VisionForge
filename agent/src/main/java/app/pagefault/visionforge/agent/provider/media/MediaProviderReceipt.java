package app.pagefault.visionforge.agent.provider.media;

/**
 * Small provider receipt safe to persist in the attempt row. Binary content never travels here.
 */
public record MediaProviderReceipt(
        String requestId,
        String remoteTaskId,
        String providerStatus,
        String usage) {
}
