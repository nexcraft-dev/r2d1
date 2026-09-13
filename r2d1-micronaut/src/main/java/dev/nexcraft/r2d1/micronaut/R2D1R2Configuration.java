package dev.nexcraft.r2d1.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.bind.annotation.Bindable;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * Cloudflare R2 settings used when Micronaut creates the DocumentStore.
 *
 * @param endpoint Cloudflare R2 S3 endpoint, required for an integration-owned client
 * @param accessKeyId R2 access key ID, required for an integration-owned client
 * @param secretAccessKey R2 secret access key, required for an integration-owned client
 * @param bucketName R2 bucket name
 * @param region S3 signing region
 */
@ConfigurationProperties("r2d1.r2")
@Requires(property = "r2d1.enabled", value = "true")
public record R2D1R2Configuration(
    @Nullable URI endpoint,
    @Nullable String accessKeyId,
    @Nullable String secretAccessKey,
    @Nullable String bucketName,
    @Bindable(defaultValue = "auto") String region) {

  @Override
  public String toString() {
    return "R2D1R2Configuration[endpoint=<redacted>, accessKeyId=<redacted>, "
        + "secretAccessKey=<redacted>, bucketName=<redacted>, region="
        + region
        + "]";
  }
}
