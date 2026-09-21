package dev.nexcraft.r2d1.micronaut;

import org.jspecify.annotations.Nullable;

/** Common nullable value exposed by Micronaut's nested R2 client configuration record. */
public interface R2D1R2ClientConfiguration {

  /**
   * Returns the low-level client override, or {@code null} to derive it from admission.
   *
   * @return low-level client concurrency override, or {@code null} to derive it from admission
   */
  @Nullable Integer maxConcurrency();
}
