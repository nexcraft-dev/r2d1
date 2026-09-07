package dev.nexcraft.r2d1.spi;

/**
 * Unchecked boundary for storage implementation failures.
 *
 * <p>This exception intentionally carries no retry semantics and prevents implementation-specific
 * exceptions from becoming part of the core SPI.
 */
public final class StorageException extends RuntimeException {

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
}
