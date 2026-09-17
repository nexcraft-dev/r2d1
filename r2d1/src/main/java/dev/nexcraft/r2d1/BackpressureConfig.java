package dev.nexcraft.r2d1;

/**
 * Immutable admission limits for one downstream I/O budget.
 *
 * @param maxConcurrency maximum number of active operations; must be positive
 * @param maxPending maximum number of queued operations; must not be negative
 */
public record BackpressureConfig(int maxConcurrency, int maxPending) {

  /** Default library limits of eight active and thirty-two pending operations. */
  public static final BackpressureConfig DEFAULT = new BackpressureConfig(8, 32);

  /**
   * Creates admission limits.
   *
   * @param maxConcurrency maximum number of active operations; must be positive
   * @param maxPending maximum number of queued operations; must not be negative
   */
  public BackpressureConfig {
    if (maxConcurrency <= 0) {
      throw new IllegalArgumentException("maxConcurrency must be greater than zero");
    }
    if (maxPending < 0) {
      throw new IllegalArgumentException("maxPending must not be negative");
    }
  }
}
