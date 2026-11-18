package app.pagefault.visionforge.agent.provider.media;

/**
 * Fetches an upstream result URL. Kept behind an interface so adapters can be contract-tested
 * without network access and so HTTPS-only policy stays in one place.
 */
@FunctionalInterface
public interface MediaResultDownloader {

    byte[] download(String url);
}
