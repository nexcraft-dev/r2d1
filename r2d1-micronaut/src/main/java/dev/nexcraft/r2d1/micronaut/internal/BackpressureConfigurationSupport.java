package dev.nexcraft.r2d1.micronaut.internal;

import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.micronaut.R2D1BackpressureConfiguration;
import org.jspecify.annotations.Nullable;

/** Resolves adapter settings over explicit JDBC aliases, global settings, and Core defaults. */
final class BackpressureConfigurationSupport {

  private BackpressureConfigurationSupport() {}

  static BackpressureConfig resolve(
      @Nullable R2D1BackpressureConfiguration adapter,
      @Nullable R2D1BackpressureConfiguration global) {
    return resolve(adapter, null, null, global);
  }

  static BackpressureConfig resolveJdbc(
      @Nullable R2D1BackpressureConfiguration adapter,
      @Nullable Integer legacyMaxConcurrency,
      @Nullable Integer legacyMaxPending,
      @Nullable R2D1BackpressureConfiguration global) {
    return resolve(adapter, legacyMaxConcurrency, legacyMaxPending, global);
  }

  private static BackpressureConfig resolve(
      @Nullable R2D1BackpressureConfiguration adapter,
      @Nullable Integer legacyMaxConcurrency,
      @Nullable Integer legacyMaxPending,
      @Nullable R2D1BackpressureConfiguration global) {
    int maxConcurrency =
        value(
            adapter == null ? null : adapter.maxConcurrency(),
            legacyMaxConcurrency,
            global == null ? null : global.maxConcurrency(),
            BackpressureConfig.DEFAULT.maxConcurrency());
    int maxPending =
        value(
            adapter == null ? null : adapter.maxPending(),
            legacyMaxPending,
            global == null ? null : global.maxPending(),
            BackpressureConfig.DEFAULT.maxPending());
    return new BackpressureConfig(maxConcurrency, maxPending);
  }

  private static int value(
      @Nullable Integer adapter,
      @Nullable Integer legacy,
      @Nullable Integer global,
      int defaultValue) {
    if (adapter != null) {
      return adapter;
    }
    if (legacy != null) {
      return legacy;
    }
    return global == null ? defaultValue : global;
  }
}
