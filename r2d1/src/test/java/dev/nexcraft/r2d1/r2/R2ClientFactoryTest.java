package dev.nexcraft.r2d1.r2;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.BackpressureConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;

class R2ClientFactoryTest {

  @Test
  void derivesOwnedClientConcurrencyFromR2Admission() {
    BackpressureConfig admission = new BackpressureConfig(16, 40);

    assertThat(R2ClientFactory.resolveClientMaxConcurrency(admission, null)).isEqualTo(16);
    assertThat(R2ClientFactory.resolveClientMaxConcurrency(admission, 24)).isEqualTo(24);
  }

  @Test
  void honorsAndWarnsAboutAnExplicitClientConcurrencyBelowAdmission() {
    Logger logger = Logger.getLogger(R2ClientFactory.class.getName());
    List<LogRecord> records = new ArrayList<>();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    Level previousLevel = logger.getLevel();
    logger.addHandler(handler);
    logger.setLevel(Level.ALL);
    try {
      assertThat(R2ClientFactory.resolveClientMaxConcurrency(new BackpressureConfig(32, 0), 8))
          .isEqualTo(8);
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
    }

    assertThat(records)
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.getLevel()).isEqualTo(Level.WARNING);
              assertThat(record.getMessage()).contains("maxConcurrency 8", "maxConcurrency 32");
            });
  }

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
