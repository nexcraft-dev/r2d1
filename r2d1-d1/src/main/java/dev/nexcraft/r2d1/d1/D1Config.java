package dev.nexcraft.r2d1.d1;

import java.util.Objects;

/**
 * Immutable connection settings for the Cloudflare D1 REST API.
 *
 * <p>{@link #toString()} does not expose the account, database, or API token values.
 *
 * @param accountId Cloudflare account identifier
 * @param databaseId D1 database identifier
 * @param apiToken API token authorized to access D1
 */
public record D1Config(String accountId, String databaseId, String apiToken) {

  /** Creates validated D1 connection settings. */
  public D1Config {
    accountId = requireText(accountId, "accountId");
    databaseId = requireText(databaseId, "databaseId");
    apiToken = requireText(apiToken, "apiToken");
  }

  @Override
  public String toString() {
    return "D1Config[accountId=<redacted>, databaseId=<redacted>, apiToken=<redacted>]";
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
