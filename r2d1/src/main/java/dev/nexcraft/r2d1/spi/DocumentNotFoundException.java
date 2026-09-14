package dev.nexcraft.r2d1.spi;

import java.util.Objects;

/**
 * Exceptional-completion result for a strict document lookup whose key does not exist.
 *
 * <p>The immutable document key is retained so future orchestration can distinguish absence from
 * other storage failures without parsing the exception message. This exception does not imply any
 * retry policy.
 */
public final class DocumentNotFoundException extends StorageException {

  /** Missing document identity retained for programmatic inspection. */
  private final DocumentKey documentKey;

  /**
   * Creates a not-found failure for one document key.
   *
   * @param documentKey missing document identity
   * @throws NullPointerException if {@code documentKey} is {@code null}
   */
  public DocumentNotFoundException(DocumentKey documentKey) {
    super(messageFor(documentKey));
    this.documentKey = documentKey;
  }

  /**
   * Returns the missing document identity.
   *
   * @return immutable missing document key
   */
  public DocumentKey documentKey() {
    return documentKey;
  }

  private static String messageFor(DocumentKey documentKey) {
    Objects.requireNonNull(documentKey, "documentKey");
    return "Document not found: " + documentKey.collection() + "/" + documentKey.id();
  }
}
