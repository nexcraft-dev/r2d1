package dev.nexcraft.r2d1.spring;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cloudflare R2 settings used when Spring Boot creates the core R2 DocumentStore.
 *
 * @param endpoint the Cloudflare R2 S3-compatible endpoint
 * @param accessKeyId the R2 access key identifier
 * @param secretAccessKey the R2 secret access key
 * @param bucketName the R2 bucket name
 * @param region the signing region, defaulting to {@code auto}
 */
@ConfigurationProperties("r2d1.r2")
public record R2D1R2Properties(
    @Nullable URI endpoint,
    @Nullable String accessKeyId,
    @Nullable String secretAccessKey,
    @Nullable String bucketName,
    @DefaultValue("auto") String region) {

  @Override
  public String toString() {
    return "R2D1R2Properties[endpoint=<redacted>, accessKeyId=<redacted>, "
        + "secretAccessKey=<redacted>, bucketName=<redacted>, region="
        + region
        + "]";
  }
}
