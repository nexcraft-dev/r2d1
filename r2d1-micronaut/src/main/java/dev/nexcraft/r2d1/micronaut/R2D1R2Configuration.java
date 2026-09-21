package dev.nexcraft.r2d1.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Creator;
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
 * @param backpressure optional active and pending R2 operation limits
 * @param client optional low-level S3 client capacity override
 */
@ConfigurationProperties("r2d1.r2")
@Requires(property = "r2d1.enabled", value = "true")
public record R2D1R2Configuration(
    @Nullable URI endpoint,
    @Nullable String accessKeyId,
    @Nullable String secretAccessKey,
    @Nullable String bucketName,
    @Bindable(defaultValue = "auto") String region,
    @Nullable BackpressureConfiguration backpressure,
    @Nullable ClientConfiguration client) {

  /** Creates R2 adapter configuration. */
  @Creator
  public R2D1R2Configuration(
      @Nullable URI endpoint,
      @Nullable String accessKeyId,
      @Nullable String secretAccessKey,
      @Nullable String bucketName,
      @Bindable(defaultValue = "auto") String region,
      @Nullable BackpressureConfiguration backpressure,
      @Nullable ClientConfiguration client) {
    this.endpoint = endpoint;
    this.accessKeyId = accessKeyId;
    this.secretAccessKey = secretAccessKey;
    this.bucketName = bucketName;
    this.region = region;
    this.backpressure = backpressure;
    this.client = client;
  }

  /**
   * Preserves the constructor available before backpressure and client properties were added.
   *
   * @param endpoint Cloudflare R2 S3 endpoint, required for an integration-owned client
   * @param accessKeyId R2 access key ID, required for an integration-owned client
   * @param secretAccessKey R2 secret access key, required for an integration-owned client
   * @param bucketName R2 bucket name
   * @param region S3 signing region
   */
  public R2D1R2Configuration(
      @Nullable URI endpoint,
      @Nullable String accessKeyId,
      @Nullable String secretAccessKey,
      @Nullable String bucketName,
      String region) {
    this(endpoint, accessKeyId, secretAccessKey, bucketName, region, null, null);
  }

  /**
   * Nested R2 active and pending request limits.
   *
   * @param maxConcurrency optional active-request limit
   * @param maxPending optional pending-request limit
   */
  @ConfigurationProperties("backpressure")
  public record BackpressureConfiguration(
      @Nullable Integer maxConcurrency, @Nullable Integer maxPending)
      implements R2D1BackpressureConfiguration {

    /** Validates each explicitly configured R2 limit. */
    public BackpressureConfiguration {
      if (maxConcurrency != null && maxConcurrency <= 0) {
        throw new ConfigurationException(
            "r2d1.r2.backpressure.max-concurrency must be greater than zero");
      }
      if (maxPending != null && maxPending < 0) {
        throw new ConfigurationException("r2d1.r2.backpressure.max-pending must not be negative");
      }
    }
  }

  /**
   * Nested low-level settings for the R2 S3-compatible client.
   *
   * @param maxConcurrency explicit client capacity, or {@code null} to derive it from admission
   */
  @ConfigurationProperties("client")
  public record ClientConfiguration(@Nullable Integer maxConcurrency)
      implements R2D1R2ClientConfiguration {

    /** Validates the explicitly configured client capacity. */
    public ClientConfiguration {
      if (maxConcurrency != null && maxConcurrency <= 0) {
        throw new ConfigurationException(
            "r2d1.r2.client.max-concurrency must be greater than zero");
      }
    }
  }

  @Override
  public String toString() {
    return "R2D1R2Configuration[endpoint=<redacted>, accessKeyId=<redacted>, "
        + "secretAccessKey=<redacted>, bucketName=<redacted>, region="
        + region
        + "]";
  }
}
