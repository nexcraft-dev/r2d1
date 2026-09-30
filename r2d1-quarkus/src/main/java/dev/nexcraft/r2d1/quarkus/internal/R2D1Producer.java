package dev.nexcraft.r2d1.quarkus.internal;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.quarkus.R2D1RuntimeConfig;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.Optional;

/** Assembles the facade lazily and validates selected wiring at runtime startup. */
@Singleton
public final class R2D1Producer {
  private final BeanSelection beans;
  private final OwnedResources resources;
  private final R2D1RuntimeConfig config;

  /** Uses CDI selection so higher-level application overrides bypass lower-level storage. */
  public R2D1Producer(
      final BeanSelection beans, final OwnedResources resources, final R2D1RuntimeConfig config) {
    this.beans = beans;
    this.resources = resources;
    this.config = config;
  }

  /** Creates a facade only when no application facade exists. */
  @Produces
  @Singleton
  @DefaultBean
  R2D1 r2d1() {
    return R2D1.builder()
        .collectionFactory(beans.required(R2D1.CollectionFactory.class, Optional.empty()))
        .build();
  }

  /** Requires the application serialization boundary before creating any managed storage. */
  @Produces
  @Singleton
  @DefaultBean
  R2D1.CollectionFactory collectionFactory() {
    final DocumentCodec codec = beans.required(DocumentCodec.class, Optional.empty());
    final DocumentStore documents = beans.required(DocumentStore.class, Optional.empty());
    final IndexStore index = beans.required(IndexStore.class, Optional.empty());
    final PersistenceCollectionFactory.CollectionInitializer initializer =
        beans.required(PersistenceCollectionFactory.CollectionInitializer.class, Optional.empty());
    return new PersistenceCollectionFactory(documents, index, codec, initializer);
  }

  /** Creates the selected authoritative store only when no application store exists. */
  @Produces
  @Singleton
  @DefaultBean
  DocumentStore documentStore() {
    final var backend =
        ConfigurationSupport.required(config.document().type(), "quarkus.r2d1.document.type");
    return switch (backend) {
      case R2 -> beans.required(R2Producer.class, Optional.empty()).create();
      case FILESYSTEM ->
          beans
              .optional(FilesystemProducer.class)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Filesystem backend requires dev.nexcraft:r2d1-filesystem"))
              .create();
    };
  }

  /** Creates the selected derived index only when no application index exists. */
  @Produces
  @Singleton
  @DefaultBean
  IndexStore indexStore() {
    final var backend =
        ConfigurationSupport.required(config.index().type(), "quarkus.r2d1.index.type");
    return switch (backend) {
      case D1 -> beans.required(D1Producer.class, Optional.empty()).create();
      case JDBC ->
          beans
              .optional(JdbcProducer.class)
              .orElseThrow(
                  () -> new IllegalStateException("JDBC backend requires dev.nexcraft:r2d1-jdbc"))
              .create();
    };
  }

  /** Requires a matching application initializer for custom indexes. */
  @Produces
  @Singleton
  @DefaultBean
  PersistenceCollectionFactory.CollectionInitializer initializer() {
    return resources.initializer(beans.required(IndexStore.class, Optional.empty()));
  }

  /** Ensures enabled wiring is resolved at startup, with partial-failure cleanup. */
  void validate(@Observes final StartupEvent event) {
    try {
      beans.required(R2D1.class, Optional.empty());
    } catch (final RuntimeException failure) {
      try {
        resources.close();
      } catch (final RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }
}
