package dev.nexcraft.r2d1;

import dev.nexcraft.r2d1.spi.DocumentKey;
import java.util.Objects;

/** Failure raised while coordinating authoritative documents with their derived index. */
public class PersistenceException extends RuntimeException {

  /**
   * Creates a persistence failure with a detail message.
   *
   * @param message failure detail
   */
  public PersistenceException(String message) {
    super(message);
  }

  /**
   * Creates a persistence failure with a detail message and cause.
   *
   * @param message failure detail
   * @param cause underlying failure
   */
  public PersistenceException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Failure after the authoritative operation succeeded but the derived index operation failed. */
  public static final class PartialFailure extends PersistenceException {

    private final DocumentKey documentKey;

    /**
     * Creates a partial persistence failure for one document.
     *
     * @param message failure detail describing the committed and failed operations
     * @param documentKey affected document identity
     * @param cause underlying index failure
     */
    public PartialFailure(String message, DocumentKey documentKey, Throwable cause) {
      super(message, cause);
      this.documentKey = Objects.requireNonNull(documentKey, "documentKey");
    }

    /**
     * Returns the affected document identity.
     *
     * @return affected document key
     */
    public DocumentKey documentKey() {
      return documentKey;
    }
  }

  /** Failure caused by disagreement between a derived index and authoritative document storage. */
  public static final class InconsistentState extends PersistenceException {

    private final DocumentKey documentKey;

    /**
     * Creates an inconsistent-state failure for one document.
     *
     * @param message consistency failure detail
     * @param documentKey inconsistent document identity
     * @param cause underlying authoritative lookup failure
     */
    public InconsistentState(String message, DocumentKey documentKey, Throwable cause) {
      super(message, cause);
      this.documentKey = Objects.requireNonNull(documentKey, "documentKey");
    }

    /**
     * Returns the inconsistent document identity.
     *
     * @return inconsistent document key
     */
    public DocumentKey documentKey() {
      return documentKey;
    }
  }
}
