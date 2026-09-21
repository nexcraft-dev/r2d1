package dev.nexcraft.r2d1.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import org.jspecify.annotations.Nullable;

/**
 * Cloudflare D1 settings used when Micronaut creates the IndexStore.
 *
 * @param accountId Cloudflare account ID
 * @param databaseId Cloudflare D1 database ID
 * @param apiToken Cloudflare API token
 */
@ConfigurationProperties("r2d1.d1")
@Requires(property = "r2d1.enabled", value = "true")
public record R2D1D1Configuration(
    @Nullable String accountId, @Nullable String databaseId, @Nullable String apiToken) {

  @Override
  public String toString() {
    return "R2D1D1Configuration[accountId=<redacted>, databaseId=<redacted>, "
        + "apiToken=<redacted>]";
  }
}
