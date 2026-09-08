package dev.nexcraft.r2d1.d1;

import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spi.StorageException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Asynchronous {@link IndexStore} backed by Cloudflare D1 as a rebuildable materialized index.
 *
 * <p>The public constructor owns one Java HTTP client, reuses it for every request, and closes it
 * in {@link #close()}. This adapter creates no executor, scheduler, or virtual thread and composes
 * all I/O through {@link CompletionStage}.
 *
 * <p>{@link #initialize(Class)} must complete before a collection is used. Concurrent first
 * initialization calls for the same collection share one schema stage, and a failed initialization
 * is not retried automatically.
 */
public final class D1IndexStore implements IndexStore, AutoCloseable {

  private final D1Transport transport;
  private final D1SchemaManager schemaManager;
  private final D1SqlCompiler sqlCompiler = new D1SqlCompiler();
  private final ConcurrentMap<String, Registration> registrations = new ConcurrentHashMap<>();
  private final boolean ownsTransport;
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Creates a D1 index store that owns its REST transport and reusable HTTP client.
   *
   * @param config Cloudflare D1 REST API connection settings
   * @throws NullPointerException if {@code config} is {@code null}
   */
  public D1IndexStore(D1Config config) {
    this(new RestD1Transport(Objects.requireNonNull(config, "config")), true);
  }

  D1IndexStore(D1Transport transport) {
    this(transport, false);
  }

  private D1IndexStore(D1Transport transport, boolean ownsTransport) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.schemaManager = new D1SchemaManager(transport);
    this.ownsTransport = ownsTransport;
  }

  /**
   * Validates document annotations and asynchronously prepares the collection's D1 schema.
   *
   * <p>Only safe additive changes are applied to an existing schema. Adding a required indexed
   * field to a populated table fails with an error that requires a rebuild.
   *
   * @param documentType document class annotated with {@code @Document}
   * @return non-null stage that completes when the schema is ready
   * @throws NullPointerException if {@code documentType} is {@code null}
   * @throws IllegalArgumentException if annotation metadata violates D1 v1 constraints
   */
  public CompletionStage<@Nullable Void> initialize(Class<?> documentType) {
    D1CollectionMetadata metadata = D1Metadata.inspect(documentType);
    Registration candidate = new Registration(metadata, new CompletableFuture<>());
    Registration registration = registrations.putIfAbsent(metadata.collection(), candidate);
    if (registration != null) {
      if (!registration.metadata().hasSameSchema(metadata)) {
        throw new IllegalArgumentException(
            "collection is already initialized with different metadata: " + metadata.collection());
      }
      return registration.initialization();
    }

    try {
      CompletionStage<@Nullable Void> initialization =
          Objects.requireNonNull(
              schemaManager.initialize(metadata), "schema manager returned a null stage");
      initialization.whenComplete(
          (ignored, failure) -> {
            if (failure == null) {
              candidate.initialization().complete(null);
            } else {
              candidate.initialization().completeExceptionally(failure);
            }
          });
    } catch (RuntimeException failure) {
      candidate
          .initialization()
          .completeExceptionally(
              failure instanceof StorageException
                  ? failure
                  : new StorageException.Operation("D1 schema initialization failed", failure));
    }
    return candidate.initialization();
  }

  @Override
  public CompletionStage<@Nullable Void> upsert(IndexEntry entry) {
    Objects.requireNonNull(entry, "entry");
    Registration registration = registrations.get(entry.documentKey().collection());
    if (registration == null) {
      return notInitialized(entry.documentKey().collection());
    }
    D1Statement statement = sqlCompiler.upsert(registration.metadata(), entry);
    return registration
        .initialization()
        .thenCompose(ignored -> execute(statement))
        .thenAccept(ignored -> {});
  }

  @Override
  public CompletionStage<IndexPage> query(IndexQuery query) {
    Objects.requireNonNull(query, "query");
    Registration registration = registrations.get(query.collection());
    if (registration == null) {
      return notInitialized(query.collection());
    }
    D1SqlCompiler.CompiledQuery compiled = sqlCompiler.query(registration.metadata(), query);
    return registration
        .initialization()
        .thenCompose(ignored -> execute(compiled.statement()))
        .thenApply(result -> sqlCompiler.page(registration.metadata(), compiled, result));
  }

  @Override
  public CompletionStage<@Nullable Void> delete(DocumentKey key) {
    Objects.requireNonNull(key, "key");
    Registration registration = registrations.get(key.collection());
    if (registration == null) {
      return notInitialized(key.collection());
    }
    D1Statement statement = sqlCompiler.delete(registration.metadata(), key);
    return registration
        .initialization()
        .thenCompose(ignored -> execute(statement))
        .thenAccept(ignored -> {});
  }

  @Override
  public void close() {
    if (ownsTransport && closed.compareAndSet(false, true)) {
      transport.close();
    }
  }

  private CompletionStage<D1Result> execute(D1Statement statement) {
    try {
      return Objects.requireNonNull(
          transport.execute(statement), "D1 transport returned a null stage");
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(
          failure instanceof StorageException
              ? failure
              : new StorageException.Operation("D1 operation failed", failure));
    }
  }

  private static <T extends @Nullable Object> CompletionStage<T> notInitialized(String collection) {
    return CompletableFuture.failedFuture(
        new StorageException.Operation("D1 collection is not initialized: " + collection));
  }

  private record Registration(
      D1CollectionMetadata metadata, CompletableFuture<@Nullable Void> initialization) {}
}
