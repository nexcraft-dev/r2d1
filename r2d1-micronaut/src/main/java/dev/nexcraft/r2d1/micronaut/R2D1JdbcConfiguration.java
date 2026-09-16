package dev.nexcraft.r2d1.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Creator;
import io.micronaut.core.bind.annotation.Bindable;
import org.jspecify.annotations.Nullable;

/**
 * Configuration for an automatically created JDBC IndexStore.
 *
 * @param datasource exact DataSource bean name, or {@code null} for normal Micronaut resolution
 * @param executor exact caller-owned Executor bean name, or {@code null} for managed execution
 * @param executionMode managed JDBC thread mode, or {@code null} for platform threads
 * @param maxConcurrency maximum number of admitted JDBC operations; the effective default is
 *     inherited by the factory when the flat property is absent
 * @param maxPending maximum number of queued JDBC operations; the effective default is inherited by
 *     the factory when the flat property is absent
 * @param backpressure optional nested JDBC backpressure overrides
 */
@ConfigurationProperties("r2d1.jdbc")
@Requires(property = "r2d1.enabled", value = "true")
public record R2D1JdbcConfiguration(
    @Nullable String datasource,
    @Nullable String executor,
    @Nullable R2D1JdbcExecutionMode executionMode,
    @Bindable(defaultValue = "4") int maxConcurrency,
    @Bindable(defaultValue = "64") int maxPending,
    @Nullable BackpressureConfiguration backpressure) {

  /** Creates JDBC adapter configuration. */
  @Creator
  public R2D1JdbcConfiguration(
      @Nullable String datasource,
      @Nullable String executor,
      @Nullable R2D1JdbcExecutionMode executionMode,
      @Bindable(defaultValue = "4") int maxConcurrency,
      @Bindable(defaultValue = "64") int maxPending,
      @Nullable BackpressureConfiguration backpressure) {
    if (maxConcurrency <= 0) {
      throw new ConfigurationException("r2d1.jdbc.max-concurrency must be greater than zero");
    }
    if (maxPending < 0) {
      throw new ConfigurationException("r2d1.jdbc.max-pending must not be negative");
    }
    this.datasource = datasource;
    this.executor = executor;
    this.executionMode = executionMode;
    this.maxConcurrency = maxConcurrency;
    this.maxPending = maxPending;
    this.backpressure = backpressure;
  }

  /**
   * Preserves the constructor available before nested backpressure properties were added.
   *
   * @param datasource exact DataSource bean name, or {@code null} for normal Micronaut resolution
   * @param executor exact caller-owned Executor bean name, or {@code null} for managed execution
   * @param executionMode managed JDBC thread mode, or {@code null} for platform threads
   * @param maxConcurrency legacy flat active-operation value
   * @param maxPending legacy flat pending-operation value
   */
  public R2D1JdbcConfiguration(
      @Nullable String datasource,
      @Nullable String executor,
      @Nullable R2D1JdbcExecutionMode executionMode,
      int maxConcurrency,
      int maxPending) {
    this(datasource, executor, executionMode, maxConcurrency, maxPending, null);
  }

  /**
   * Nested JDBC active and pending operation limits.
   *
   * @param maxConcurrency optional active-operation limit
   * @param maxPending optional pending-operation limit
   */
  @ConfigurationProperties("backpressure")
  public record BackpressureConfiguration(
      @Nullable Integer maxConcurrency, @Nullable Integer maxPending)
      implements R2D1BackpressureConfiguration {

    /** Validates each explicitly configured JDBC limit. */
    public BackpressureConfiguration {
      if (maxConcurrency != null && maxConcurrency <= 0) {
        throw new ConfigurationException(
            "r2d1.jdbc.backpressure.max-concurrency must be greater than zero");
      }
      if (maxPending != null && maxPending < 0) {
        throw new ConfigurationException("r2d1.jdbc.backpressure.max-pending must not be negative");
      }
    }
  }
}
