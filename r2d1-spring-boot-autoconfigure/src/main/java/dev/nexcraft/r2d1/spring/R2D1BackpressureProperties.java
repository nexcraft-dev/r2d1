package dev.nexcraft.r2d1.spring;

import org.jspecify.annotations.Nullable;

/**
 * Optional active and pending operation limits for one R2D1 adapter.
 *
 * @param maxConcurrency optional active-operation limit; {@code null} inherits the next scope
 * @param maxPending optional pending-operation limit; {@code null} inherits the next scope
 */
public record R2D1BackpressureProperties(
    @Nullable Integer maxConcurrency, @Nullable Integer maxPending) {

  /** Validates limits when they are explicitly configured. */
  public R2D1BackpressureProperties {
    if (maxConcurrency != null && maxConcurrency <= 0) {
      throw new IllegalArgumentException("backpressure.max-concurrency must be greater than zero");
    }
    if (maxPending != null && maxPending < 0) {
      throw new IllegalArgumentException("backpressure.max-pending must not be negative");
    }
  }
}
