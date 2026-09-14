package dev.nexcraft.r2d1.spi;

/**
 * Unchecked exceptional-completion boundary for storage implementation failures.
 *
 * <p>This exception intentionally carries no retry semantics and prevents implementation-specific
 * exceptions from becoming part of the core SPI. Asynchronous storage operations complete
 * exceptionally with this type rather than throwing it synchronously for operational failures.
 */
public class StorageException extends RuntimeException {

  /**
   * Creates a storage failure with a detail message.
   *
   * @param message failure detail
   */
  public StorageException(String message) {
    super(message);
  }

  /**
   * Creates a storage failure with a detail message and cause.
   *
   * @param message failure detail
   * @param cause underlying implementation failure
   */
  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Failure caused by rejected storage authentication or insufficient authorization. */
  public static final class Access extends StorageException {

    /**
     * Creates an access failure with a detail message.
     *
     * @param message failure detail
     */
    public Access(String message) {
      super(message);
    }

    /**
     * Creates an access failure with a detail message and cause.
     *
     * @param message failure detail
     * @param cause underlying implementation failure
     */
    public Access(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Failure caused by a storage service that is temporarily unavailable. */
  public static final class Unavailable extends StorageException {

    /**
     * Creates an availability failure with a detail message.
     *
     * @param message failure detail
     */
    public Unavailable(String message) {
      super(message);
    }

    /**
     * Creates an availability failure with a detail message and cause.
     *
     * @param message failure detail
     * @param cause underlying implementation failure
     */
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Failure caused by an invalid storage operation or protocol response. */
  public static final class Operation extends StorageException {

    /**
     * Creates an operation failure with a detail message.
     *
     * @param message failure detail
     */
    public Operation(String message) {
      super(message);
    }

    /**
     * Creates an operation failure with a detail message and cause.
     *
     * @param message failure detail
     * @param cause underlying implementation failure
     */
    public Operation(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
