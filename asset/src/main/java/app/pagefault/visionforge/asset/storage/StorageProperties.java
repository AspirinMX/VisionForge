package app.pagefault.visionforge.asset.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the S3-compatible storage adapter.
 *
 * <p>{@code endpoint} is used by the server for reads and writes. {@code publicEndpoint} is used
 * only when signing URLs for browsers; both hosts must be reachable by their respective callers.
 */
@ConfigurationProperties(prefix = "visionforge.storage")
public record StorageProperties(
        String backend,
        String endpoint,
        String publicEndpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        Boolean pathStyle,
        Duration presignTtl,
        Duration uploadSessionTtl,
        Long maxUploadBytes) {

    private static final Duration DEFAULT_PRESIGN_TTL = Duration.ofMinutes(15);
    private static final Duration DEFAULT_UPLOAD_SESSION_TTL = Duration.ofHours(1);
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;

    public String backendOrDefault() {
        return blank(backend) ? "s3" : backend.trim().toLowerCase();
    }

    public String regionOrDefault() {
        return blank(region) ? "us-east-1" : region.trim();
    }

    public boolean pathStyleOrDefault() {
        return pathStyle == null || pathStyle;
    }

    public Duration presignTtlOrDefault() {
        return presignTtl == null || presignTtl.isNegative() || presignTtl.isZero()
                ? DEFAULT_PRESIGN_TTL
                : presignTtl;
    }

    public Duration uploadSessionTtlOrDefault() {
        return uploadSessionTtl == null
                        || uploadSessionTtl.isNegative()
                        || uploadSessionTtl.isZero()
                ? DEFAULT_UPLOAD_SESSION_TTL
                : uploadSessionTtl;
    }

    public long maxUploadBytesOrDefault() {
        return maxUploadBytes == null || maxUploadBytes <= 0
                ? DEFAULT_MAX_UPLOAD_BYTES
                : maxUploadBytes;
    }

    public boolean s3() {
        return "s3".equals(backendOrDefault());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
