package dev.nexcraft.r2d1.jdbc;

import java.util.Objects;

/**
 * Immutable configuration for an R2D1-managed JDBC execution resource.
 *
 * @param mode execution mode selected by the caller
 * @param maxConcurrency maximum number of concurrently running JDBC operations
 * @param maxPending maximum number of admitted JDBC operations waiting to run
 */
public record JdbcExecutionConfig(JdbcExecutionMode mode, int maxConcurrency, int maxPending) {

  /** Creates validated execution configuration. */
  public JdbcExecutionConfig {
    mode = Objects.requireNonNull(mode, "mode");
    if (maxConcurrency <= 0) {
      throw new IllegalArgumentException("maxConcurrency must be greater than zero");
    }
    if (maxPending < 0) {
      throw new IllegalArgumentException("maxPending must not be negative");
    }
  }
}
