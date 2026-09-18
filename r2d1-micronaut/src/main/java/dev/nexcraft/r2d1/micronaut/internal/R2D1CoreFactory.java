package dev.nexcraft.r2d1.micronaut.internal;

import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.micronaut.IndexBackend;
import dev.nexcraft.r2d1.micronaut.R2D1IndexConfiguration;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.ConfigurationException;

/** Creates the framework-neutral R2D1 facade from resolved storage components. */
@Factory
@Requires(property = "r2d1.enabled", value = "true")
final class R2D1CoreFactory {

  @Context
  @Requires(missingBeans = R2D1.class)
  R2D1 r2d1(
      BeanProvider<R2D1.CollectionFactory> collectionFactories,
      BeanProvider<DocumentStore> documentStores,
      BeanProvider<IndexStore> indexStores,
      BeanProvider<PersistenceCollectionFactory.CollectionInitializer> initializers,
      R2D1IndexConfiguration indexConfiguration,
      Environment environment) {
    R2D1.CollectionFactory collectionFactory = optionalCollectionFactory(collectionFactories);
    if (collectionFactory == null) {
      DocumentStore documentStore = requiredDocumentStore(documentStores, environment);
      IndexStore indexStore = requiredIndexStore(indexStores, indexConfiguration, environment);
      PersistenceCollectionFactory.CollectionInitializer initializer =
          requiredInitializer(initializers);
      collectionFactory = new PersistenceCollectionFactory(documentStore, indexStore, initializer);
    }
    return R2D1.builder().collectionFactory(collectionFactory).build();
  }

  private static R2D1.CollectionFactory optionalCollectionFactory(
      BeanProvider<R2D1.CollectionFactory> provider) {
    return BeanSelection.optional(provider, "R2D1.CollectionFactory");
  }

  private static DocumentStore requiredDocumentStore(
      BeanProvider<DocumentStore> provider, Environment environment) {
    if (!provider.isPresent()) {
      String message =
          environment.containsProperties("r2d1.r2")
              ? "R2 configuration is present but no DocumentStore was created; verify r2d1.r2 configuration or define a DocumentStore bean"
              : "No DocumentStore bean exists; configure r2d1.r2 or define a DocumentStore bean";
      throw new ConfigurationException(message);
    }
    return requiredUnique(provider, "DocumentStore");
  }

  private static IndexStore requiredIndexStore(
      BeanProvider<IndexStore> provider,
      R2D1IndexConfiguration configuration,
      Environment environment) {
    if (!provider.isPresent()) {
      IndexBackend backend = configuration.type();
      if (backend == null) {
        String configuredType =
            environment.getProperty("r2d1.index.type", String.class).orElse(null);
        if (configuredType != null) {
          throw new ConfigurationException("r2d1.index.type must be jdbc or d1: " + configuredType);
        }
        throw new ConfigurationException(
            "r2d1.index.type must be jdbc or d1 unless an IndexStore bean is provided");
      }
      throw new ConfigurationException(
          "R2D1 index backend '"
              + backend.name().toLowerCase(java.util.Locale.ROOT)
              + "' was selected but no IndexStore was created; add the matching R2D1 adapter or define an IndexStore bean");
    }
    return requiredUnique(provider, "IndexStore");
  }

  private static PersistenceCollectionFactory.CollectionInitializer requiredInitializer(
      BeanProvider<PersistenceCollectionFactory.CollectionInitializer> provider) {
    if (!provider.isPresent()) {
      throw new ConfigurationException(
          "No collection initializer exists for the selected IndexStore; define a PersistenceCollectionFactory.CollectionInitializer bean for a custom IndexStore");
    }
    return requiredUnique(provider, "PersistenceCollectionFactory.CollectionInitializer");
  }

  private static <T> T requiredUnique(BeanProvider<T> provider, String description) {
    try {
      return provider.get();
    } catch (io.micronaut.context.exceptions.NonUniqueBeanException failure) {
      throw new ConfigurationException(
          "Multiple " + description + " beans are available without a resolvable primary bean",
          failure);
    }
  }
}
