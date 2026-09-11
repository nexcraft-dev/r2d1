package dev.nexcraft.r2d1.integration.cloudflare;

import dev.nexcraft.r2d1.d1.D1Config;
import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.r2.R2Config;
import dev.nexcraft.r2d1.r2.R2DocumentStore;
import java.net.URI;
import java.util.UUID;

public record CloudflareIntegrationConfig(R2Config r2, D1Config d1) {

  private static final String CONFIRMATION = "R2D1_IT_CONFIRM_DEDICATED_RESOURCES";

  public static CloudflareIntegrationConfig load() {
    if (!"true".equals(System.getenv(CONFIRMATION))) {
      throw new IllegalStateException(CONFIRMATION + " must be exactly true");
    }
    return new CloudflareIntegrationConfig(
        new R2Config(
            URI.create(requireEnvironment("R2D1_IT_R2_ENDPOINT")),
            requireEnvironment("R2D1_IT_R2_ACCESS_KEY_ID"),
            requireEnvironment("R2D1_IT_R2_SECRET_ACCESS_KEY"),
            requireEnvironment("R2D1_IT_R2_BUCKET_NAME")),
        new D1Config(
            requireEnvironment("R2D1_IT_D1_ACCOUNT_ID"),
            requireEnvironment("R2D1_IT_D1_DATABASE_ID"),
            requireEnvironment("R2D1_IT_D1_API_TOKEN")));
  }

  public R2DocumentStore openR2() {
    return new R2DocumentStore(r2);
  }

  public D1IndexStore openD1() {
    return new D1IndexStore(d1);
  }

  public R2Config invalidR2Credentials() {
    String nonce = UUID.randomUUID().toString();
    return new R2Config(
        r2.endpoint(),
        "invalid-access-" + nonce,
        "invalid-secret-" + nonce,
        r2.bucketName(),
        r2.region());
  }

  public D1Config invalidD1Token() {
    return new D1Config(d1.accountId(), d1.databaseId(), "invalid-token-" + UUID.randomUUID());
  }

  private static String requireEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing required integration test environment variable: " + name);
    }
    return value;
  }
}
