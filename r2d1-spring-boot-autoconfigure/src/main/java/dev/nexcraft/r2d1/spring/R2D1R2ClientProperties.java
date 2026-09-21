package dev.nexcraft.r2d1.spring;

import org.jspecify.annotations.Nullable;

/**
 * Optional low-level settings for the R2 S3-compatible client.
 *
 * @param maxConcurrency explicit AWS SDK HTTP client capacity; {@code null} derives it from R2
 *     admission
 */
public record R2D1R2ClientProperties(@Nullable Integer maxConcurrency) {

  /** Validates the explicitly configured AWS SDK HTTP client capacity. */
  public R2D1R2ClientProperties {
    if (maxConcurrency != null && maxConcurrency <= 0) {
      throw new IllegalArgumentException(
          "r2d1.r2.client.max-concurrency must be greater than zero");
    }
  }
}
