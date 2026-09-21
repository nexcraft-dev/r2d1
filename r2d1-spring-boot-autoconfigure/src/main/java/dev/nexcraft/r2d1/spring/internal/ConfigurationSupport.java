package dev.nexcraft.r2d1.spring.internal;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** Small validation helpers used by Spring bean factories. */
final class ConfigurationSupport {

  private ConfigurationSupport() {}

  static String requireText(@Nullable String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(property + " must be configured with a non-blank value");
    }
    return value;
  }

  static Path requirePath(@Nullable Path value, String property) {
    if (value == null) {
      throw new IllegalStateException(property + " must be configured");
    }
    return value;
  }
}
