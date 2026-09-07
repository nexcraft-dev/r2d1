package dev.nexcraft.r2d1.spi;

import java.util.concurrent.CompletionStage;

/**
 * Asynchronous authoritative persistence for serialized documents.
 *
 * <p>Implementations derive any physical location from {@link DocumentKey}; no implementation
 * reference is stored in the derived index. Operations on this store and {@link IndexStore} are not
 * one atomic transaction, so callers must tolerate temporary cross-store inconsistency.
 *
 * <p>Every operation returns a non-null {@link CompletionStage}. Storage failures are reported by
 * exceptional completion with {@link StorageException}; they are not returned in a result wrapper.
 * Adapters may use {@code CompletableFuture} internally, but the contract exposes only {@link
 * CompletionStage}.
 *
 * <p>The SPI does not own or create executors, schedulers, or virtual threads. Implementations may
 * use non-blocking transports, but no callback thread or executor is guaranteed. The contract also
 * makes no guarantee that cancelling a returned stage cancels the underlying operation, and it
 * defines no timeout policy.
 */
public interface DocumentStore {

  /**
   * Asynchronously creates or replaces the document identified by {@code key}.
   *
   * @param key document identity
   * @param document serialized document content
   * @return a non-null stage that completes with {@code null}, or exceptionally with {@link
   *     StorageException} if the storage implementation cannot complete the operation
   * @throws NullPointerException if {@code key} or {@code document} is {@code null}
   */
  CompletionStage<Void> put(DocumentKey key, StoredDocument document);

  /**
   * Asynchronously retrieves an authoritative document using strict lookup semantics.
   *
   * @param key document identity
   * @return a non-null stage that completes with the stored content, exceptionally with {@link
   *     DocumentNotFoundException} when the document does not exist, or exceptionally with another
   *     {@link StorageException} when the storage implementation fails
   * @throws NullPointerException if {@code key} is {@code null}
   */
  CompletionStage<StoredDocument> get(DocumentKey key);

  /**
   * Asynchronously deletes a document when it exists and otherwise has no effect.
   *
   * @param key document identity
   * @return a non-null stage that completes with {@code null}, or exceptionally with {@link
   *     StorageException} if the storage implementation cannot complete the operation
   * @throws NullPointerException if {@code key} is {@code null}
   */
  CompletionStage<Void> delete(DocumentKey key);
}
