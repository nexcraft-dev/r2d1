package dev.nexcraft.r2d1.spi;

import java.util.Optional;

/**
 * Authoritative persistence for serialized documents.
 *
 * <p>Implementations derive any physical location from {@link DocumentKey}; no implementation
 * reference is stored in the derived index. Operations on this store and {@link IndexStore} are not
 * one atomic transaction, so callers must tolerate temporary cross-store inconsistency.
 */
public interface DocumentStore {

  /**
   * Creates or replaces the document identified by {@code key}.
   *
   * @param key document identity
   * @param document serialized document content
   * @throws StorageException if the storage implementation cannot complete the operation
   */
  void put(DocumentKey key, StoredDocument document) throws StorageException;

  /**
   * Retrieves an authoritative document when it exists.
   *
   * @param key document identity
   * @return stored content, or empty when the document does not exist
   * @throws StorageException if the storage implementation cannot complete the operation
   */
  Optional<StoredDocument> get(DocumentKey key) throws StorageException;

  /**
   * Deletes a document when it exists and otherwise has no effect.
   *
   * @param key document identity
   * @throws StorageException if the storage implementation cannot complete the operation
   */
  void delete(DocumentKey key) throws StorageException;
}
