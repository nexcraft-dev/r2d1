package dev.nexcraft.r2d1.quarkus.internal;

import java.util.Optional;

/** Produces property-oriented validation errors without disclosing configured values. */
public final class ConfigurationSupport {
  private ConfigurationSupport() {}

  /** Requires an optional value without rendering it in diagnostics. */
  public static <T> T required(final Optional<T> value, final String property) {
    return value.orElseThrow(() -> new IllegalStateException(property + " must be configured"));
  }

  /** Requires a nonblank credential or resource name without rendering its contents. */
  public static String text(final Optional<String> value, final String property) {
    final String text = required(value, property);
    if (text.isBlank()) {
      throw new IllegalStateException(property + " must not be blank");
    }
    return text;
  }
}
