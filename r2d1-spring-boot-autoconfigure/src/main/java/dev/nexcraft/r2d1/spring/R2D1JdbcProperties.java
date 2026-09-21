package dev.nexcraft.r2d1.spring;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.Name;

/**
 * JDBC settings used when Spring Boot creates the optional JDBC IndexStore.
 *
 * @param datasource the optional Spring bean name for the application DataSource
 * @param executor the optional Spring bean name for an application Executor
 * @param executionMode the managed execution mode, defaulting to platform threads
 * @param legacyMaxConcurrency the optional legacy flat active-operation override
 * @param legacyMaxPending the optional legacy flat pending-operation override
 * @param backpressure the optional nested JDBC backpressure overrides
 */
@ConfigurationProperties("r2d1.jdbc")
public record R2D1JdbcProperties(
    @Nullable String datasource,
    @Nullable String executor,
    @Nullable R2D1JdbcExecutionMode executionMode,
    @Name("max-concurrency") @Nullable Integer legacyMaxConcurrency,
    @Name("max-pending") @Nullable Integer legacyMaxPending,
    @Nullable R2D1BackpressureProperties backpressure) {

  /** Creates JDBC adapter properties. */
  @ConstructorBinding
  public R2D1JdbcProperties(
      @Nullable String datasource,
      @Nullable String executor,
      @Nullable R2D1JdbcExecutionMode executionMode,
      @Name("max-concurrency") @Nullable Integer legacyMaxConcurrency,
      @Name("max-pending") @Nullable Integer legacyMaxPending,
      @Nullable R2D1BackpressureProperties backpressure) {
    if (legacyMaxConcurrency != null && legacyMaxConcurrency <= 0) {
      throw new IllegalArgumentException("r2d1.jdbc.max-concurrency must be greater than zero");
    }
    if (legacyMaxPending != null && legacyMaxPending < 0) {
      throw new IllegalArgumentException("r2d1.jdbc.max-pending must not be negative");
    }
    this.datasource = datasource;
    this.executor = executor;
    this.executionMode = executionMode;
    this.legacyMaxConcurrency = legacyMaxConcurrency;
    this.legacyMaxPending = legacyMaxPending;
    this.backpressure = backpressure;
  }

  /**
   * Preserves the constructor available before nested backpressure properties were added.
   *
   * @param datasource optional application {@code DataSource} bean name
   * @param executor optional caller-owned {@code Executor} bean name
   * @param executionMode managed JDBC execution mode
   * @param maxConcurrency legacy active-operation limit
   * @param maxPending legacy pending-operation limit
   */
  public R2D1JdbcProperties(
      @Nullable String datasource,
      @Nullable String executor,
      @Nullable R2D1JdbcExecutionMode executionMode,
      int maxConcurrency,
      int maxPending) {
    this(datasource, executor, executionMode, maxConcurrency, maxPending, null);
  }

  /**
   * Returns the legacy JDBC active-operation value, or its historical default when absent.
   *
   * @return configured legacy active-operation limit, or {@code 4}
   */
  public int maxConcurrency() {
    return legacyMaxConcurrency == null ? 4 : legacyMaxConcurrency;
  }

  /**
   * Returns the legacy JDBC pending-operation value, or its historical default when absent.
   *
   * @return configured legacy pending-operation limit, or {@code 64}
   */
  public int maxPending() {
    return legacyMaxPending == null ? 64 : legacyMaxPending;
  }
}
