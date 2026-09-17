package dev.nexcraft.r2d1.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Creator;
import org.jspecify.annotations.Nullable;

/**
 * Cloudflare D1 settings used when Micronaut creates the IndexStore.
 *
 * @param accountId Cloudflare account ID
 * @param databaseId Cloudflare D1 database ID
 * @param apiToken Cloudflare API token
 * @param backpressure optional active and pending D1 operation limits
 */
@ConfigurationProperties("r2d1.d1")
@Requires(property = "r2d1.enabled", value = "true")
public record R2D1D1Configuration(
    @Nullable String accountId,
    @Nullable String databaseId,
    @Nullable String apiToken,
    @Nullable BackpressureConfiguration backpressure) {

  /** Creates D1 adapter configuration. */
  @Creator
  public R2D1D1Configuration(
      @Nullable String accountId,
      @Nullable String databaseId,
      @Nullable String apiToken,
      @Nullable BackpressureConfiguration backpressure) {
    this.accountId = accountId;
    this.databaseId = databaseId;
    this.apiToken = apiToken;
    this.backpressure = backpressure;
  }

  /**
   * Preserves the constructor available before backpressure properties were added.
   *
   * @param accountId Cloudflare account ID
   * @param databaseId Cloudflare D1 database ID
   * @param apiToken Cloudflare API token
   */
  public R2D1D1Configuration(
      @Nullable String accountId, @Nullable String databaseId, @Nullable String apiToken) {
    this(accountId, databaseId, apiToken, null);
  }

  /**
   * Nested D1 active and pending request limits.
   *
   * @param maxConcurrency optional active-request limit
   * @param maxPending optional pending-request limit
   */
  @ConfigurationProperties("backpressure")
  public record BackpressureConfiguration(
      @Nullable Integer maxConcurrency, @Nullable Integer maxPending)
      implements R2D1BackpressureConfiguration {

    /** Validates each explicitly configured D1 limit. */
    public BackpressureConfiguration {
      if (maxConcurrency != null && maxConcurrency <= 0) {
        throw new ConfigurationException(
            "r2d1.d1.backpressure.max-concurrency must be greater than zero");
      }
      if (maxPending != null && maxPending < 0) {
        throw new ConfigurationException("r2d1.d1.backpressure.max-pending must not be negative");
      }
    }
  }

  @Override
  public String toString() {
    return "R2D1D1Configuration[accountId=<redacted>, databaseId=<redacted>, "
        + "apiToken=<redacted>]";
  }
}
