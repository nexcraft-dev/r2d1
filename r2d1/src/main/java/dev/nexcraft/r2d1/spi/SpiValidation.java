package dev.nexcraft.r2d1.spi;

import java.util.Objects;

final class SpiValidation {

  private SpiValidation() {}

  static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
