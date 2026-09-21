package dev.nexcraft.r2d1.spring;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Cloudflare D1 settings used when Spring Boot creates the core D1 IndexStore.
 *
 * @param accountId the Cloudflare account identifier
 * @param databaseId the Cloudflare D1 database identifier
 * @param apiToken the Cloudflare API token
 * @param backpressure optional active and pending D1 operation limits
 */
@ConfigurationProperties("r2d1.d1")
public record R2D1D1Properties(
    @Nullable String accountId,
    @Nullable String databaseId,
    @Nullable String apiToken,
    @Nullable R2D1BackpressureProperties backpressure) {

  /** Creates D1 adapter properties. */
  @ConstructorBinding
  public R2D1D1Properties(
      @Nullable String accountId,
      @Nullable String databaseId,
      @Nullable String apiToken,
      @Nullable R2D1BackpressureProperties backpressure) {
    this.accountId = accountId;
    this.databaseId = databaseId;
    this.apiToken = apiToken;
    this.backpressure = backpressure;
  }

  /**
   * Preserves the constructor available before backpressure properties were added.
   *
   * @param accountId the Cloudflare account identifier
   * @param databaseId the Cloudflare D1 database identifier
   * @param apiToken the Cloudflare API token
   */
  public R2D1D1Properties(
      @Nullable String accountId, @Nullable String databaseId, @Nullable String apiToken) {
    this(accountId, databaseId, apiToken, null);
  }

  @Override
  public String toString() {
    return "R2D1D1Properties[accountId=<redacted>, databaseId=<redacted>, "
        + "apiToken=<redacted>]";
  }
}
