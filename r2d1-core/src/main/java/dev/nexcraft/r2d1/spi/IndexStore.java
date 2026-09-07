package dev.nexcraft.r2d1.spi;

/**
 * Derived, rebuildable storage for indexed fields and document discovery.
 *
 * <p>Query results contain only authoritative document identities for later resolution through
 * {@link DocumentStore}. Operations on this store and the document store are not one atomic
 * transaction, so callers must tolerate temporary cross-store inconsistency.
 */
public interface IndexStore {

  /**
   * Creates or replaces the derived index entry for a document.
   *
   * @param entry derived indexed fields
   * @throws StorageException if the storage implementation cannot complete the operation
   */
  void upsert(IndexEntry entry) throws StorageException;

  /**
   * Queries derived index data using filtering, sorting, and keyset pagination.
   *
   * @param query validated technology-neutral query
   * @return matching authoritative document identities and an optional continuation cursor
   * @throws StorageException if the storage implementation cannot complete the operation
   */
  IndexPage query(IndexQuery query) throws StorageException;

  /**
   * Deletes a derived index entry when it exists and otherwise has no effect.
   *
   * @param key authoritative document identity
   * @throws StorageException if the storage implementation cannot complete the operation
   */
  void delete(DocumentKey key) throws StorageException;
}
