package dev.nexcraft.r2d1.spring;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JDBC settings used when Spring Boot creates the optional JDBC IndexStore.
 *
 * @param datasource the optional Spring bean name for the application DataSource
 * @param executor the optional Spring bean name for an application Executor
 * @param executionMode the managed execution mode, defaulting to platform threads
 * @param maxConcurrency the maximum number of admitted JDBC operations
 * @param maxPending the maximum number of queued JDBC operations
 */
@ConfigurationProperties("r2d1.jdbc")
public record R2D1JdbcProperties(
    @Nullable String datasource,
    @Nullable String executor,
    @Nullable R2D1JdbcExecutionMode executionMode,
    @DefaultValue("4") int maxConcurrency,
    @DefaultValue("64") int maxPending) {

  /** Creates validated JDBC integration properties. */
  public R2D1JdbcProperties {
    if (maxConcurrency <= 0) {
      throw new IllegalArgumentException("r2d1.jdbc.max-concurrency must be greater than zero");
    }
    if (maxPending < 0) {
      throw new IllegalArgumentException("r2d1.jdbc.max-pending must not be negative");
    }
  }
}
