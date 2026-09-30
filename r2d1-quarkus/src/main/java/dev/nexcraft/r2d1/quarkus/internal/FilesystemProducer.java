package dev.nexcraft.r2d1.quarkus.internal;

import dev.nexcraft.r2d1.filesystem.FileSystemDocumentStore;
import dev.nexcraft.r2d1.quarkus.R2D1RuntimeConfig;
import dev.nexcraft.r2d1.spi.DocumentStore;
import jakarta.inject.Singleton;
import java.util.concurrent.Executor;

/** Isolates optional filesystem adapter references from the base CDI integration. */
@Singleton
public final class FilesystemProducer {
  private final R2D1RuntimeConfig config;
  private final BeanSelection beans;

  /** Uses the application-owned executor for blocking filesystem calls. */
  public FilesystemProducer(final R2D1RuntimeConfig config, final BeanSelection beans) {
    this.config = config;
    this.beans = beans;
  }

  /** Creates filesystem storage without taking ownership of its executor. */
  public DocumentStore create() {
    final var settings = config.filesystem();
    final var root =
        ConfigurationSupport.required(
            settings.rootDirectory(), "quarkus.r2d1.filesystem.root-directory");
    final var executor = beans.required(Executor.class, settings.executor());
    return new FileSystemDocumentStore(root, executor);
  }
}
