package dev.nexcraft.r2d1.spring;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Top-level switch for the optional R2D1 Spring Boot integration.
 *
 * @param enabled whether the integration should create R2D1 beans
 * @param backpressure optional global active and pending operation limits
 */
@ConfigurationProperties("r2d1")
public record R2D1Properties(
    @DefaultValue("false") boolean enabled, @Nullable R2D1BackpressureProperties backpressure) {

  /** Creates top-level integration properties. */
  @ConstructorBinding
  public R2D1Properties(
      @DefaultValue("false") boolean enabled, @Nullable R2D1BackpressureProperties backpressure) {
    this.enabled = enabled;
    this.backpressure = backpressure;
  }

  /**
   * Preserves the constructor available before backpressure properties were added.
   *
   * @param enabled whether the integration should create R2D1 beans
   */
  public R2D1Properties(boolean enabled) {
    this(enabled, null);
  }
}
