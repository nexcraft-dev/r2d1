package dev.nexcraft.r2d1.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.bind.annotation.Bindable;
import org.jspecify.annotations.Nullable;

/**
 * Configuration for an automatically created JDBC IndexStore.
 *
 * @param datasource exact DataSource bean name, or {@code null} for normal Micronaut resolution
 * @param executor exact caller-owned Executor bean name, or {@code null} for managed execution
 * @param executionMode managed JDBC thread mode, or {@code null} for platform threads
 * @param maxConcurrency positive maximum number of concurrently running JDBC operations
 * @param maxPending non-negative maximum number of admitted pending JDBC operations
 */
@ConfigurationProperties("r2d1.jdbc")
@Requires(property = "r2d1.enabled", value = "true")
public record R2D1JdbcConfiguration(
    @Nullable String datasource,
    @Nullable String executor,
    @Nullable R2D1JdbcExecutionMode executionMode,
    @Bindable(defaultValue = "4") int maxConcurrency,
    @Bindable(defaultValue = "64") int maxPending) {

  /** Creates validated JDBC integration configuration. */
  public R2D1JdbcConfiguration {
    if (maxConcurrency <= 0) {
      throw new ConfigurationException("r2d1.jdbc.max-concurrency must be greater than zero");
    }
    if (maxPending < 0) {
      throw new ConfigurationException("r2d1.jdbc.max-pending must not be negative");
    }
  }
}
