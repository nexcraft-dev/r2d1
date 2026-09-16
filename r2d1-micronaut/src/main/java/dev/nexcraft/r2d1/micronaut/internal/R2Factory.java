package dev.nexcraft.r2d1.micronaut.internal;

import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.micronaut.R2D1Configuration;
import dev.nexcraft.r2d1.micronaut.R2D1R2Configuration;
import dev.nexcraft.r2d1.r2.R2Config;
import dev.nexcraft.r2d1.r2.R2DocumentStore;
import dev.nexcraft.r2d1.spi.DocumentStore;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.net.URI;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/** Creates the optional Cloudflare R2 document component. */
@Factory
@Requires(property = "r2d1.enabled", value = "true")
@Requires(classes = R2DocumentStore.class)
final class R2Factory {

  private static final Logger LOGGER = Logger.getLogger(R2Factory.class.getName());

  @Bean(preDestroy = "close")
  @Singleton
  @Requires(missingBeans = DocumentStore.class)
  R2DocumentStore r2DocumentStore(
      R2D1R2Configuration configuration,
      R2D1Configuration globalConfiguration,
      BeanProvider<S3AsyncClient> clients) {
    String bucketName =
        BeanSelection.requireText(configuration.bucketName(), "r2d1.r2.bucket-name");
    BackpressureConfig backpressure =
        BackpressureConfigurationSupport.resolve(
            configuration.backpressure(), globalConfiguration.backpressure());
    @Nullable Integer clientMaxConcurrency =
        configuration.client() == null ? null : configuration.client().maxConcurrency();
    S3AsyncClient client = BeanSelection.optional(clients, "S3AsyncClient");
    if (client != null) {
      if (clientMaxConcurrency != null) {
        LOGGER.warning(
            "r2d1.r2.client.max-concurrency is not applied to a caller-owned S3AsyncClient; "
                + "configure that client directly");
      }
      return new R2DocumentStore(client, bucketName, backpressure);
    }

    URI endpoint = configuration.endpoint();
    if (endpoint == null) {
      throw new io.micronaut.context.exceptions.ConfigurationException(
          "r2d1.r2.endpoint must be configured when no S3AsyncClient bean is available");
    }
    R2Config config =
        new R2Config(
            endpoint,
            BeanSelection.requireText(configuration.accessKeyId(), "r2d1.r2.access-key-id"),
            BeanSelection.requireText(configuration.secretAccessKey(), "r2d1.r2.secret-access-key"),
            bucketName,
            configuration.region());
    return new R2DocumentStore(config, backpressure, clientMaxConcurrency);
  }
}
