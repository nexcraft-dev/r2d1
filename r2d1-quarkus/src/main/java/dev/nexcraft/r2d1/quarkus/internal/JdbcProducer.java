package dev.nexcraft.r2d1.quarkus.internal;

import dev.nexcraft.r2d1.jdbc.JdbcExecution;
import dev.nexcraft.r2d1.jdbc.JdbcExecutionConfig;
import dev.nexcraft.r2d1.jdbc.JdbcExecutionMode;
import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
import dev.nexcraft.r2d1.quarkus.R2D1RuntimeConfig;
import dev.nexcraft.r2d1.spi.IndexStore;
import jakarta.inject.Singleton;
import java.util.concurrent.Executor;
import javax.sql.DataSource;

/** Isolates optional JDBC types and owns only integration-created execution resources. */
@Singleton
public final class JdbcProducer {
  private final R2D1RuntimeConfig config;
  private final BeanSelection beans;
  private final OwnedResources resources;

  /** Receives runtime settings without taking ownership of an application datasource. */
  public JdbcProducer(
      final R2D1RuntimeConfig config, final BeanSelection beans, final OwnedResources resources) {
    this.config = config;
    this.beans = beans;
    this.resources = resources;
  }

  /** Creates the JDBC index and associates initialization with the exact created store. */
  public IndexStore create() {
    final var source = beans.required(DataSource.class, config.jdbc().datasource());
    final var execution = beans.optional(JdbcExecution.class).orElseGet(this::execution);
    final var store = new JdbcIndexStore(source, execution);
    resources.index(store, store::initialize);
    return store;
  }

  private JdbcExecution execution() {
    final var settings = config.jdbc();
    if (settings.maxConcurrency() <= 0 || settings.maxPending() < 0) {
      throw new IllegalStateException(
          "quarkus.r2d1.jdbc.max-concurrency must be positive and max-pending nonnegative");
    }
    if (settings.executor().isPresent()) return externalExecution(settings);
    final var mode =
        settings.executionMode().orElse(R2D1RuntimeConfig.ExecutionMode.PLATFORM_THREAD);
    final var executionMode =
        mode == R2D1RuntimeConfig.ExecutionMode.VIRTUAL_THREAD
            ? JdbcExecutionMode.VIRTUAL_THREAD
            : JdbcExecutionMode.PLATFORM_THREAD;
    return resources.own(
        JdbcExecution.create(
            new JdbcExecutionConfig(
                executionMode, settings.maxConcurrency(), settings.maxPending())));
  }

  private JdbcExecution externalExecution(final R2D1RuntimeConfig.JdbcConfig settings) {
    if (settings.executionMode().isPresent()) {
      throw new IllegalStateException(
          "quarkus.r2d1.jdbc.execution-mode conflicts with jdbc.executor; the caller owns the thread model");
    }
    final var executor = beans.required(Executor.class, settings.executor());
    return resources.own(
        JdbcExecution.using(executor, settings.maxConcurrency(), settings.maxPending()));
  }
}
