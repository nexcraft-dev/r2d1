package dev.nexcraft.r2d1;

import dev.nexcraft.r2d1.spi.StoredDocument;

/**
 * Converts domain documents to and from the bytes owned by the authoritative document store.
 *
 * <p>The core module supplies no default serialization format. Implementations may use an
 * application-selected codec, but implementation-specific types do not become part of this
 * contract. Codec failures propagate through the synchronous collection operation that invoked
 * them.
 */
public interface DocumentCodec {

  /**
   * Serializes a non-null domain document.
   *
   * @param document document to serialize
   * @return serialized document content
   * @throws NullPointerException if {@code document} is {@code null}
   */
  StoredDocument serialize(Object document);

  /**
   * Deserializes stored content as the requested domain type.
   *
   * @param document serialized document content
   * @param documentType requested domain type
   * @param <T> domain document type
   * @return deserialized document
   * @throws NullPointerException if either argument is {@code null}
   */
  <T> T deserialize(StoredDocument document, Class<T> documentType);
}
