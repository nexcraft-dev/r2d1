package dev.nexcraft.r2d1.internal.persistence;

import dev.nexcraft.r2d1.DocumentCodec;
import java.util.Objects;

/** Validated identity of one collection codec. */
record CodecDescriptor(String format, String codec) {

  CodecDescriptor {
    requireText(format, "codec format");
    requireText(codec, "codec id");
  }

  static CodecDescriptor from(DocumentCodec<?> codec) {
    Objects.requireNonNull(codec, "codec");
    return new CodecDescriptor(
        Objects.requireNonNull(codec.format(), "codec format"),
        Objects.requireNonNull(codec.id(), "codec id"));
  }

  private static String requireText(String value, String description) {
    Objects.requireNonNull(value, description);
    if (value.isBlank()) {
      throw new IllegalArgumentException(description + " must not be blank");
    }
    return value;
  }
}
