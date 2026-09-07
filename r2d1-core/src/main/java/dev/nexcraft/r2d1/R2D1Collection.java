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
   * Starts an immutable indexed query.
   *
   * @return a new query
   */
  Query<T> query();
}
