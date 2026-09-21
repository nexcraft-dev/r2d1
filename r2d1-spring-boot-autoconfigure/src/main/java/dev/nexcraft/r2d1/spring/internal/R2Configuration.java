package dev.nexcraft.r2d1.spring.internal;

import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.r2.R2Config;
import dev.nexcraft.r2d1.r2.R2DocumentStore;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spring.R2D1Properties;
import dev.nexcraft.r2d1.spring.R2D1R2Properties;
import java.net.URI;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/** Creates the optional Cloudflare R2 document component. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "r2d1.document", name = "type", havingValue = "r2")
public class R2Configuration {

  private static final Logger LOGGER = Logger.getLogger(R2Configuration.class.getName());

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(DocumentStore.class)
  R2DocumentStore r2DocumentStore(
      R2D1R2Properties properties,
      R2D1Properties globalProperties,
      ObjectProvider<S3AsyncClient> clients) {
    String bucketName =
        ConfigurationSupport.requireText(properties.bucketName(), "r2d1.r2.bucket-name");
    BackpressureConfig backpressure =
        BackpressureConfigurationSupport.resolve(
            properties.backpressure(), globalProperties.backpressure());
    @Nullable S3AsyncClient client = BeanSelection.optional(clients, "S3AsyncClient");
    if (client != null) {
      if (properties.client() != null && properties.client().maxConcurrency() != null) {
        LOGGER.warning(
            "r2d1.r2.client.max-concurrency is not applied to a caller-owned S3AsyncClient; "
                + "configure that client directly");
      }
      return new R2DocumentStore(client, bucketName, backpressure);
    }

    URI endpoint = properties.endpoint();
    if (endpoint == null) {
      throw new IllegalStateException(
          "r2d1.r2.endpoint must be configured when no S3AsyncClient bean is available");
    }
    R2Config config =
        new R2Config(
            endpoint,
            ConfigurationSupport.requireText(properties.accessKeyId(), "r2d1.r2.access-key-id"),
            ConfigurationSupport.requireText(
                properties.secretAccessKey(), "r2d1.r2.secret-access-key"),
            bucketName,
            properties.region());
    Integer clientMaxConcurrency =
        properties.client() == null ? null : properties.client().maxConcurrency();
    return new R2DocumentStore(config, backpressure, clientMaxConcurrency);
  }
}
