package dev.nexcraft.r2d1.spi;

import java.util.Arrays;
import java.util.Objects;

/** Immutable serialized document content owned by the authoritative document store. */
public final class StoredDocument {

  private final byte[] content;

  /**
   * Creates a stored document by copying its content.
   *
   * @param content serialized document bytes
   * @throws NullPointerException if {@code content} is {@code null}
   */
  public StoredDocument(byte[] content) {
    this.content = Objects.requireNonNull(content, "content").clone();
  }

  /**
   * Returns an independent copy of the serialized content.
   *
   * @return copied content bytes
   */
  public byte[] content() {
    return content.clone();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || (other instanceof StoredDocument that && Arrays.equals(content, that.content));
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(content);
  }
}
