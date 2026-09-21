package dev.nexcraft.r2d1.spring.internal;

import dev.nexcraft.r2d1.r2.R2Config;
import dev.nexcraft.r2d1.r2.R2DocumentStore;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spring.R2D1R2Properties;
import java.net.URI;
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

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(DocumentStore.class)
  R2DocumentStore r2DocumentStore(
      R2D1R2Properties properties, ObjectProvider<S3AsyncClient> clients) {
    String bucketName =
        ConfigurationSupport.requireText(properties.bucketName(), "r2d1.r2.bucket-name");
    @Nullable S3AsyncClient client = BeanSelection.optional(clients, "S3AsyncClient");
    if (client != null) {
      return new R2DocumentStore(client, bucketName);
    }

    URI endpoint = properties.endpoint();
    if (endpoint == null) {
      throw new IllegalStateException(
          "r2d1.r2.endpoint must be configured when no S3AsyncClient bean is available");
    }
    return new R2DocumentStore(
        new R2Config(
            endpoint,
            ConfigurationSupport.requireText(properties.accessKeyId(), "r2d1.r2.access-key-id"),
            ConfigurationSupport.requireText(
                properties.secretAccessKey(), "r2d1.r2.secret-access-key"),
            bucketName,
            properties.region()));
  }
}
