package dev.nexcraft.r2d1;

/**
 * Converts one concrete document type to and from authoritative-store bytes.
 *
 * @param <T> concrete document type handled by this codec
 */
public interface DocumentCodec<T> {

  /**
   * Returns the stable implementation identity for this codec.
   *
   * @return codec identity such as {@code avaje-jsonb-3}
   */
  String id();

  /**
   * Returns the storage format handled by this codec.
   *
   * @return format such as {@code json}
   */
  String format();

  /**
   * Encodes a document into bytes.
   *
   * <p>Codec instances are used concurrently by their collection and must be thread-safe.
   *
   * @param document document to encode
   * @return encoded bytes
   * @throws NullPointerException if {@code document} is {@code null}
   */
  byte[] encode(T document);

  /**
   * Decodes bytes into a document.
   *
   * <p>Codec instances are used concurrently by their collection and must be thread-safe.
   *
   * @param data encoded document bytes
   * @return decoded document
   * @throws NullPointerException if {@code data} is {@code null}
   */
  T decode(byte[] data);
}
