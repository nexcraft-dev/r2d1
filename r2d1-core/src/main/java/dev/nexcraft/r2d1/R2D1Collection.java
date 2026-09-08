package dev.nexcraft.r2d1;

import java.util.Optional;

/**
 * A named document collection exposed by an R2D1 adapter.
 *
 * <p>Document identifiers are non-blank strings so one identifier can be used consistently as a
 * document key and index value. Implementations must fail fast for null documents and null or blank
 * identifiers.
 *
 * @param <T> document type
 */
public interface R2D1Collection<T> {

  /**
   * Creates or replaces a document using the value of its {@code @Id}-annotated member.
   *
   * @param document document to store
   */
  void put(T document);

  /**
   * Finds a document by its identifier.
   *
   * @param id non-blank document identifier
   * @return the document, or an empty optional when no document has that identifier
   */
  Optional<T> get(String id);

  /**
   * Deletes a document by its identifier. Deleting an absent document has no effect.
   *
   * @param id non-blank document identifier
   */
  void delete(String id);

  /**
   * Rebuilds this collection's derived index from authoritative documents.
   *
   * <p>This synchronous maintenance operation is intended to run while application writes to the
   * collection are paused. It is idempotent for an unchanged authoritative collection, but it is
   * not atomic: a failure after index rows are cleared can leave the derived index incomplete.
   * Resolve the failure and invoke this method again to restore the projection.
   */
  void rebuildIndex();

  /**
   * Starts an immutable indexed query.
   *
   * @return a new query
   */
  Query<T> query();
}
