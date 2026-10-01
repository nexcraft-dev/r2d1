package dev.nexcraft.r2d1.quarkus.internal;

import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.spi.IndexStore;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Deque;
import org.jspecify.annotations.Nullable;

/**
 * Closes only integration-created resources, in reverse creation order, including failed startup.
 */
@Singleton
public final class OwnedResources implements AutoCloseable {
  private final Deque<AutoCloseable> resources = new ArrayDeque<>();
  private @Nullable IndexStore index;
  private PersistenceCollectionFactory.@Nullable CollectionInitializer initializer;
  private boolean closed;

  /** Registers a resource only when the integration created it. */
  public synchronized <T extends AutoCloseable> T own(final T resource) {
    if (closed) {
      throw new IllegalStateException("R2D1 integration resources are already closed");
    }
    resources.push(resource);
    return resource;
  }

  /** Associates initialization with the exact integration-created index. */
  public synchronized void index(
      final IndexStore store,
      final PersistenceCollectionFactory.CollectionInitializer collectionInitializer) {
    index = store;
    initializer = collectionInitializer;
  }

  /** Returns initialization only for the exact selected owned index. */
  public synchronized PersistenceCollectionFactory.CollectionInitializer initializer(
      final IndexStore selected) {
    if (selected != index || initializer == null) {
      throw new IllegalStateException(
          "An application IndexStore requires a matching CollectionInitializer bean");
    }
    return initializer;
  }

  /** Closes owned resources once and still attempts later resources if a close fails. */
  @Override
  @PreDestroy
  public synchronized void close() {
    if (closed) return;
    closed = true;
    RuntimeException failure = null;
    while (!resources.isEmpty()) {
      try {
        resources.pop().close();
      } catch (final Exception cause) {
        if (failure == null)
          failure = new IllegalStateException("Failed to close R2D1-owned resources");
        failure.addSuppressed(cause);
      }
    }
    if (failure != null) throw failure;
  }
}
