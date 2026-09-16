package dev.nexcraft.r2d1.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Creator;
import io.micronaut.core.bind.annotation.Bindable;
import org.jspecify.annotations.Nullable;

/**
 * Top-level switch for the optional R2D1 Micronaut integration.
 *
 * @param enabled whether Micronaut should create R2D1 integration beans
 * @param backpressure optional global active and pending operation limits
 */
@ConfigurationProperties("r2d1")
public record R2D1Configuration(
    @Bindable(defaultValue = "false") boolean enabled,
    @Nullable BackpressureConfiguration backpressure) {

  /** Creates top-level integration configuration. */
  @Creator
  public R2D1Configuration(
      @Bindable(defaultValue = "false") boolean enabled,
      @Nullable BackpressureConfiguration backpressure) {
    this.enabled = enabled;
    this.backpressure = backpressure;
  }

  /**
   * Preserves the constructor available before backpressure properties were added.
   *
   * @param enabled whether Micronaut should create R2D1 integration beans
   */
  public R2D1Configuration(boolean enabled) {
    this(enabled, null);
  }

  /**
   * Nested global active and pending operation limits.
   *
   * @param maxConcurrency optional global active-operation limit
   * @param maxPending optional global pending-operation limit
   */
  @ConfigurationProperties("backpressure")
  public record BackpressureConfiguration(
      @Nullable Integer maxConcurrency, @Nullable Integer maxPending)
      implements R2D1BackpressureConfiguration {

    /** Validates each explicitly configured global limit. */
    public BackpressureConfiguration {
      if (maxConcurrency != null && maxConcurrency <= 0) {
        throw new ConfigurationException("backpressure.max-concurrency must be greater than zero");
      }
      if (maxPending != null && maxPending < 0) {
        throw new ConfigurationException("backpressure.max-pending must not be negative");
      }
    }
  }
}
