package dev.nexcraft.r2d1.quarkus.internal;

import dev.nexcraft.r2d1.quarkus.R2D1RuntimeConfig;
import dev.nexcraft.r2d1.r2.R2Config;
import dev.nexcraft.r2d1.r2.R2DocumentStore;
import dev.nexcraft.r2d1.spi.DocumentStore;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/** Creates R2 storage on demand, preserving ownership of application S3 clients. */
@Singleton
public final class R2Producer {
  private final R2D1RuntimeConfig config;
  private final BeanSelection beans;
  private final OwnedResources resources;

  /** Receives runtime settings and the integration resource owner. */
  public R2Producer(
      final R2D1RuntimeConfig config, final BeanSelection beans, final OwnedResources resources) {
    this.config = config;
    this.beans = beans;
    this.resources = resources;
  }

  /** Creates the selected R2 store without issuing network requests. */
  public DocumentStore create() {
    final var settings = config.r2();
    final String bucket =
        ConfigurationSupport.text(settings.bucketName(), "quarkus.r2d1.r2.bucket-name");
    final var client = beans.optional(S3AsyncClient.class);
    if (client.isPresent()) return resources.own(new R2DocumentStore(client.get(), bucket));
    final var endpoint =
        ConfigurationSupport.required(settings.endpoint(), "quarkus.r2d1.r2.endpoint");
    final var access =
        ConfigurationSupport.text(settings.accessKeyId(), "quarkus.r2d1.r2.access-key-id");
    final var secret =
        ConfigurationSupport.text(settings.secretAccessKey(), "quarkus.r2d1.r2.secret-access-key");
    return resources.own(
        new R2DocumentStore(new R2Config(endpoint, access, secret, bucket, settings.region())));
  }
}
