package dev.nexcraft.r2d1.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ConfigurationContractTest {

  @Test
  void secretConfigurationStringsAreRedacted() {
    R2D1R2Configuration r2 =
        new R2D1R2Configuration(
            URI.create("https://private.example.com"),
            "access-value",
            "secret-value",
            "bucket-value",
            "auto");
    R2D1D1Configuration d1 =
        new R2D1D1Configuration("account-value", "database-value", "token-value");

    assertThat(r2.toString())
        .doesNotContain("private.example.com", "access-value", "secret-value", "bucket-value")
        .contains("<redacted>", "region=auto");
    assertThat(d1.toString())
        .doesNotContain("account-value", "database-value", "token-value")
        .contains("<redacted>");
  }

  @Test
  void generatedMetadataContainsTheSupportedProperties() throws Exception {
    String metadata;
    try (var input =
        getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
      assertThat(input).isNotNull();
      metadata = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    assertThat(metadata)
        .contains(
            "r2d1.enabled",
            "r2d1.index.type",
            "r2d1.jdbc.datasource",
            "r2d1.jdbc.executor",
            "r2d1.jdbc.execution-mode",
            "r2d1.jdbc.max-concurrency",
            "r2d1.jdbc.max-pending",
            "r2d1.r2.endpoint",
            "r2d1.r2.access-key-id",
            "r2d1.r2.secret-access-key",
            "r2d1.r2.bucket-name",
            "r2d1.r2.region",
            "r2d1.d1.account-id",
            "r2d1.d1.database-id",
            "r2d1.d1.api-token");
  }
}
