package app.pagefault.visionforge.asset.storage.s3;

import java.net.URI;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.pagefault.visionforge.asset.storage.ObjectStorage;
import app.pagefault.visionforge.asset.storage.StorageProperties;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Wires an S3-compatible client. RustFS, MinIO, Ceph and AWS S3 all use this adapter; only the
 * endpoint, region and path-style flag differ.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "visionforge.storage",
        name = "backend",
        havingValue = "s3",
        matchIfMissing = true)
public class S3StorageConfiguration {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.regionOrDefault()))
                .endpointOverride(uri(properties.endpoint(), "endpoint"))
                .forcePathStyle(properties.pathStyleOrDefault())
                .credentialsProvider(credentials(properties))
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(StorageProperties properties) {
        String publicEndpoint = properties.publicEndpoint();
        URI endpoint = uri(
                publicEndpoint == null || publicEndpoint.isBlank()
                        ? properties.endpoint()
                        : publicEndpoint,
                "public-endpoint");
        return S3Presigner.builder()
                .region(Region.of(properties.regionOrDefault()))
                .endpointOverride(endpoint)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleOrDefault())
                        .build())
                .credentialsProvider(credentials(properties))
                .build();
    }

    @Bean
    public ObjectStorage objectStorage(
            S3Client s3Client, S3Presigner s3Presigner, StorageProperties properties, Clock clock) {
        return new S3ObjectStorage(
                s3Client, s3Presigner, require(properties.bucket(), "bucket"), clock);
    }

    private static AwsCredentialsProvider credentials(StorageProperties properties) {
        String accessKey = properties.accessKey();
        String secretKey = properties.secretKey();
        if (accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }

    private static URI uri(String value, String name) {
        return URI.create(require(value, name));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("visionforge.storage." + name + " must be set");
        }
        return value;
    }
}
