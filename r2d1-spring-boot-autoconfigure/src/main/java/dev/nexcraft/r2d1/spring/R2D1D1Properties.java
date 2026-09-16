package dev.nexcraft.r2d1.spring;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cloudflare D1 settings used when Spring Boot creates the core D1 IndexStore.
 *
 * @param accountId the Cloudflare account identifier
 * @param databaseId the Cloudflare D1 database identifier
 * @param apiToken the Cloudflare API token
 */
@ConfigurationProperties("r2d1.d1")
public record R2D1D1Properties(
    @Nullable String accountId, @Nullable String databaseId, @Nullable String apiToken) {

  @Override
  public String toString() {
    return "R2D1D1Properties[accountId=<redacted>, databaseId=<redacted>, "
        + "apiToken=<redacted>]";
  }
}
