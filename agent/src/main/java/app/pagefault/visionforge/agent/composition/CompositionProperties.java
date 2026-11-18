package app.pagefault.visionforge.agent.composition;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "visionforge.composition")
public record CompositionProperties(
        String ffmpegPath,
        Long partSizeBytes,
        Duration timeout,
        Duration presignTtl) {

    private static final long DEFAULT_PART_SIZE = 8L * 1024 * 1024;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DEFAULT_PRESIGN_TTL = Duration.ofHours(2);

    public String ffmpegPathOrDefault() {
        return ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
    }

    public long partSizeBytesOrDefault() {
        return partSizeBytes == null || partSizeBytes < 5L * 1024 * 1024
                ? DEFAULT_PART_SIZE
                : partSizeBytes;
    }

    public Duration timeoutOrDefault() {
        return timeout == null || timeout.isNegative() || timeout.isZero()
                ? DEFAULT_TIMEOUT
                : timeout;
    }

    public Duration presignTtlOrDefault() {
        return presignTtl == null || presignTtl.isNegative() || presignTtl.isZero()
                ? DEFAULT_PRESIGN_TTL
                : presignTtl;
    }
}
