package dev.nexcraft.r2d1.r2;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;

class R2ClientFactoryTest {

  @Test
  void appliesCloudflareR2S3CompatibilitySettings() {
    S3Configuration configuration = R2ClientFactory.serviceConfiguration();

    assertThat(configuration.pathStyleAccessEnabled()).isTrue();
    assertThat(configuration.chunkedEncodingEnabled()).isFalse();
  }

  @Test
  void appliesExplicitEndpointAndRegionWithoutConnecting() {
    URI endpoint = URI.create("https://account-id.r2.cloudflarestorage.com");
    R2Config config = new R2Config(endpoint, "access", "secret", "documents", "custom");

    try (S3AsyncClient client = R2ClientFactory.create(config)) {
      assertThat(client.serviceClientConfiguration().endpointOverride()).hasValue(endpoint);
      assertThat(client.serviceClientConfiguration().region()).isEqualTo(Region.of("custom"));
    }
  }
}
