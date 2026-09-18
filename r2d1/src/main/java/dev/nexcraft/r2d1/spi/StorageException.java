package dev.nexcraft.r2d1.spi;

import java.util.Objects;

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

  /** Failure caused by a collection being opened with an incompatible storage format. */
  public static final class CodecMismatch extends StorageException {

    /** Collection whose metadata does not match the current registration. */
    private final String collection;

    /** Format recorded in the authoritative metadata table. */
    private final String storedFormat;

    /** Codec recorded in the authoritative metadata table. */
    private final String storedCodec;

    /** Format supplied by the current registration. */
    private final String currentFormat;

    /** Codec supplied by the current registration. */
    private final String currentCodec;

    /**
     * Creates a codec mismatch failure.
     *
     * @param collection collection whose codec metadata differs
     * @param storedFormat format recorded in storage
     * @param storedCodec codec recorded in storage
     * @param currentFormat format supplied by the current registration
     * @param currentCodec codec supplied by the current registration
     */
    public CodecMismatch(
        String collection,
        String storedFormat,
        String storedCodec,
        String currentFormat,
        String currentCodec) {
      super(
          "codec format mismatch for collection "
              + collection
              + ": stored format="
              + storedFormat
              + ", codec="
              + storedCodec
              + "; current format="
              + currentFormat
              + ", codec="
              + currentCodec);
      this.collection = Objects.requireNonNull(collection, "collection");
      this.storedFormat = Objects.requireNonNull(storedFormat, "storedFormat");
      this.storedCodec = Objects.requireNonNull(storedCodec, "storedCodec");
      this.currentFormat = Objects.requireNonNull(currentFormat, "currentFormat");
      this.currentCodec = Objects.requireNonNull(currentCodec, "currentCodec");
    }

    /**
     * Returns the affected collection name.
     *
     * @return collection name
     */
    public String collection() {
      return collection;
    }

    /**
     * Returns the format recorded in storage.
     *
     * @return stored format
     */
    public String storedFormat() {
      return storedFormat;
    }

    /**
     * Returns the codec identity recorded in storage.
     *
     * @return stored codec identity
     */
    public String storedCodec() {
      return storedCodec;
    }

    /**
     * Returns the format supplied by the current registration.
     *
     * @return current format
     */
    public String currentFormat() {
      return currentFormat;
    }

    /**
     * Returns the codec identity supplied by the current registration.
     *
     * @return current codec identity
     */
    public String currentCodec() {
      return currentCodec;
    }
  }

  /** Failure raised when a codec cannot encode or decode one document. */
  public static final class CodecFailure extends StorageException {

    /** Collection whose document could not be processed. */
    private final String collection;

    /** Codec operation that failed. */
    private final String operation;

    /** Format used by the codec. */
    private final String format;

    /** Codec identity used by the collection. */
    private final String codec;

    /**
     * Creates a codec operation failure while retaining the original cause.
     *
     * @param collection collection whose document could not be processed
     * @param operation codec operation such as {@code encode} or {@code decode}
     * @param format codec format
     * @param codec codec identity
     * @param cause original codec failure
     */
    public CodecFailure(
        String collection, String operation, String format, String codec, Throwable cause) {
      super(
          "document codec "
              + operation
              + " failed for collection "
              + collection
              + " (format="
              + format
              + ", codec="
              + codec
              + ")",
          cause);
      this.collection = Objects.requireNonNull(collection, "collection");
      this.operation = Objects.requireNonNull(operation, "operation");
      this.format = Objects.requireNonNull(format, "format");
      this.codec = Objects.requireNonNull(codec, "codec");
      Objects.requireNonNull(cause, "cause");
    }

    /**
     * Returns the affected collection name.
     *
     * @return collection name
     */
    public String collection() {
      return collection;
    }

    /**
     * Returns the codec operation that failed.
     *
     * @return operation name
     */
    public String operation() {
      return operation;
    }

    /**
     * Returns the codec format.
     *
     * @return codec format
     */
    public String format() {
      return format;
    }

    /**
     * Returns the codec identity.
     *
     * @return codec identity
     */
    public String codec() {
      return codec;
    }
  }
}
