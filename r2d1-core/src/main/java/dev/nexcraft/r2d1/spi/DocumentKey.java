package dev.nexcraft.r2d1.spi;

/**
 * Logical identity of one stored document.
 *
 * <p>Document identifiers are scoped to a collection. Values are retained exactly as supplied; this
 * type does not trim or normalize them.
 *
 * @param collection non-blank collection name
 * @param id non-blank document identifier within the collection
 */
public record DocumentKey(String collection, String id) {

  /** Creates a validated document key. */
  public DocumentKey {
    collection = SpiValidation.requireText(collection, "collection");
    id = SpiValidation.requireText(id, "id");
  }
}
