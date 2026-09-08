package dev.nexcraft.r2d1.spi;

import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * Asynchronous, derived, rebuildable storage for indexed fields and document discovery.
 *
 * <p>Query results contain only authoritative document identities for later resolution through
 * {@link DocumentStore}. Operations on this store and the document store are not one atomic
 * transaction, so callers must tolerate temporary cross-store inconsistency.
 *
 * <p>Every operation returns a non-null {@link CompletionStage}. Storage failures are reported by
 * exceptional completion with {@link StorageException}. Adapters may use {@code CompletableFuture}
 * internally, but the contract exposes only {@code CompletionStage}.
 *
 * <p>The SPI does not own or create executors, schedulers, or virtual threads. Implementations may
 * use non-blocking transports, but no callback thread or executor is guaranteed. The contract also
 * makes no guarantee that cancelling a returned stage cancels the underlying operation, and it
 * defines no timeout policy.
 */
public interface IndexStore {

  /**
   * Asynchronously creates or replaces the derived index entry for a document.
   *
   * @param entry derived indexed fields
   * @return a non-null stage that completes with {@code null}, or exceptionally with {@link
   *     StorageException} if the storage implementation cannot complete the operation
   * @throws NullPointerException if {@code entry} is {@code null}
   */
  CompletionStage<@Nullable Void> upsert(IndexEntry entry);

  /**
   * Asynchronously queries derived index data using filtering, sorting, and keyset pagination.
   *
   * @param query validated technology-neutral query
   * @return a non-null stage that completes with matching authoritative document identities and an
   *     optional continuation cursor, or exceptionally with {@link StorageException} if the storage
   *     implementation cannot complete the operation
   * @throws NullPointerException if {@code query} is {@code null}
   */
  CompletionStage<IndexPage> query(IndexQuery query);

  /**
   * Asynchronously deletes a derived index entry when it exists and otherwise has no effect.
   *
   * @param key authoritative document identity
   * @return a non-null stage that completes with {@code null}, or exceptionally with {@link
   *     StorageException} if the storage implementation cannot complete the operation
   * @throws NullPointerException if {@code key} is {@code null}
   */
  CompletionStage<@Nullable Void> delete(DocumentKey key);
}
