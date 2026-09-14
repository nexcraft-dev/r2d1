package dev.nexcraft.r2d1.micronaut.internal;

import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.d1.D1Config;
import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.micronaut.R2D1D1Configuration;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;

/** Creates the optional Cloudflare D1 index components. */
@Factory
@Requires(property = "r2d1.enabled", value = "true")
@Requires(property = "r2d1.index.type", value = "d1")
@Requires(classes = D1IndexStore.class)
final class D1Factory {

  @Bean(preDestroy = "close")
  @Singleton
  @Requires(missingBeans = IndexStore.class)
  D1IndexStore d1IndexStore(
      R2D1D1Configuration configuration, BeanProvider<HttpClient> httpClients) {
    D1Config config =
        new D1Config(
            BeanSelection.requireText(configuration.accountId(), "r2d1.d1.account-id"),
            BeanSelection.requireText(configuration.databaseId(), "r2d1.d1.database-id"),
            BeanSelection.requireText(configuration.apiToken(), "r2d1.d1.api-token"));
    HttpClient httpClient = BeanSelection.optional(httpClients, "java.net.http.HttpClient");
    return httpClient == null ? new D1IndexStore(config) : new D1IndexStore(config, httpClient);
  }

  @Singleton
  @Requires(missingBeans = PersistenceCollectionFactory.CollectionInitializer.class)
  PersistenceCollectionFactory.CollectionInitializer d1CollectionInitializer(
      D1IndexStore indexStore) {
    return indexStore::initialize;
  }
}
