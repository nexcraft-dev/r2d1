package dev.nexcraft.r2d1.spring.internal;

import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.d1.D1Config;
import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spring.R2D1D1Properties;
import dev.nexcraft.r2d1.spring.R2D1Properties;
import java.net.http.HttpClient;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the optional Cloudflare D1 index component. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "r2d1.index", name = "type", havingValue = "d1")
public class D1Configuration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(IndexStore.class)
  D1IndexStore d1IndexStore(
      R2D1D1Properties properties,
      R2D1Properties globalProperties,
      ObjectProvider<HttpClient> httpClients) {
    D1Config config =
        new D1Config(
            ConfigurationSupport.requireText(properties.accountId(), "r2d1.d1.account-id"),
            ConfigurationSupport.requireText(properties.databaseId(), "r2d1.d1.database-id"),
            ConfigurationSupport.requireText(properties.apiToken(), "r2d1.d1.api-token"));
    @Nullable HttpClient client = BeanSelection.optional(httpClients, "java.net.http.HttpClient");
    BackpressureConfig backpressure =
        BackpressureConfigurationSupport.resolve(
            properties.backpressure(), globalProperties.backpressure());
    return client == null
        ? new D1IndexStore(config, backpressure)
        : new D1IndexStore(config, client, backpressure);
  }

  @Bean
  @ConditionalOnBean(D1IndexStore.class)
  @ConditionalOnMissingBean(PersistenceCollectionFactory.CollectionInitializer.class)
  PersistenceCollectionFactory.CollectionInitializer d1CollectionInitializer(
      D1IndexStore indexStore) {
    return indexStore::initialize;
  }
}
