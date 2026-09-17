package dev.nexcraft.r2d1.spi;

/** Failure indicating that an operation could not enter a bounded downstream I/O budget. */
public final class AdmissionRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an admission rejection with a detail message.
   *
   * @param message rejection detail
   */
  public AdmissionRejectedException(String message) {
    super(message);
  }

  /**
   * Creates an admission rejection with a detail message and cause.
   *
   * @param message rejection detail
   * @param cause underlying provider failure, when admission could not be evaluated
   */
  public AdmissionRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
