package dev.nexcraft.r2d1.micronaut;

import org.jspecify.annotations.Nullable;

/** Common nullable values exposed by Micronaut's nested backpressure configuration records. */
public interface R2D1BackpressureConfiguration {

  /**
   * Returns the active-operation override, or {@code null} to inherit.
   *
   * @return active-operation override, or {@code null} to inherit
   */
  @Nullable Integer maxConcurrency();

  /**
   * Returns the pending-operation override, or {@code null} to inherit.
   *
   * @return pending-operation override, or {@code null} to inherit
   */
  @Nullable Integer maxPending();
}
