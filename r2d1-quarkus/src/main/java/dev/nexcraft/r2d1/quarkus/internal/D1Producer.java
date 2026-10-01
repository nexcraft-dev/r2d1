package dev.nexcraft.r2d1.quarkus.internal;

import dev.nexcraft.r2d1.d1.D1Config;
import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.quarkus.R2D1RuntimeConfig;
import dev.nexcraft.r2d1.spi.IndexStore;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;

/** Creates D1 indexing without altering the library HTTP transport or client ownership. */
@Singleton
public final class D1Producer {
  private final R2D1RuntimeConfig config;
  private final BeanSelection beans;
  private final OwnedResources resources;

  /** Receives runtime settings and the integration resource owner. */
  public D1Producer(
      final R2D1RuntimeConfig config, final BeanSelection beans, final OwnedResources resources) {
    this.config = config;
    this.beans = beans;
    this.resources = resources;
  }

  /** Creates the selected D1 store and its matching collection initializer. */
  public IndexStore create() {
    final var settings = config.d1();
    final var adapterConfig =
        new D1Config(
            ConfigurationSupport.text(settings.accountId(), "quarkus.r2d1.d1.account-id"),
            ConfigurationSupport.text(settings.databaseId(), "quarkus.r2d1.d1.database-id"),
            ConfigurationSupport.text(settings.apiToken(), "quarkus.r2d1.d1.api-token"));
    final var client = beans.optional(HttpClient.class);
    final var store =
        resources.own(
            client.isPresent()
                ? new D1IndexStore(adapterConfig, client.get())
                : new D1IndexStore(adapterConfig));
    resources.index(store, store::initialize);
    return store;
  }
}
