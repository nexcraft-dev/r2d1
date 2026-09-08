package dev.nexcraft.r2d1.spi;

/**
 * Opaque cursor for continuing an authoritative document listing.
 *
 * <p>The cursor value is interpreted only by the {@link DocumentStore} implementation that created
 * it. Callers must pass the value back unchanged and must not infer storage-specific semantics.
 *
 * @param value non-blank opaque cursor value
 */
public record DocumentCursor(String value) {

  /** Creates a validated document cursor. */
  public DocumentCursor {
    value = SpiValidation.requireText(value, "value");
  }
}
