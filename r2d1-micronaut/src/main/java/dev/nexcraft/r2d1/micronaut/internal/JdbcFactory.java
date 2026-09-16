package dev.nexcraft.r2d1.micronaut.internal;

import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.jdbc.JdbcExecution;
import dev.nexcraft.r2d1.jdbc.JdbcExecutionConfig;
import dev.nexcraft.r2d1.jdbc.JdbcExecutionMode;
import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
import dev.nexcraft.r2d1.micronaut.R2D1Configuration;
import dev.nexcraft.r2d1.micronaut.R2D1JdbcConfiguration;
import dev.nexcraft.r2d1.micronaut.R2D1JdbcExecutionMode;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.inject.Singleton;
import java.util.concurrent.Executor;
import javax.sql.DataSource;

/** Creates the optional JDBC index components. */
@Factory
@Requires(property = "r2d1.enabled", value = "true")
@Requires(property = "r2d1.index.type", value = "jdbc")
@Requires(classes = JdbcIndexStore.class)
final class JdbcFactory {

  @Bean(preDestroy = "close")
  @Singleton
  @Requires(missingBeans = JdbcExecution.class)
  JdbcExecution jdbcExecution(
      R2D1JdbcConfiguration configuration,
      R2D1Configuration globalConfiguration,
      BeanProvider<Executor> executors,
      Environment environment) {
    BackpressureConfig backpressure =
        BackpressureConfigurationSupport.resolveJdbc(
            configuration.backpressure(),
            environment.getProperty("r2d1.jdbc.max-concurrency", Integer.class).orElse(null),
            environment.getProperty("r2d1.jdbc.max-pending", Integer.class).orElse(null),
            globalConfiguration.backpressure());
    String executorName = configuration.executor();
    String configuredMode =
        environment.getProperty("r2d1.jdbc.execution-mode", String.class).orElse(null);
    if (executorName != null) {
      if (configuredMode != null || configuration.executionMode() != null) {
        throw new ConfigurationException(
            "r2d1.jdbc.execution-mode cannot be configured with r2d1.jdbc.executor; the caller-owned executor defines its thread model");
      }
      Executor executor =
          BeanSelection.required(executors, executorName, "Executor", "r2d1.jdbc.executor");
      return JdbcExecution.using(
          executor, backpressure.maxConcurrency(), backpressure.maxPending());
    }

    JdbcExecutionMode mode = resolveExecutionMode(configuredMode, configuration.executionMode());
    return JdbcExecution.create(
        new JdbcExecutionConfig(mode, backpressure.maxConcurrency(), backpressure.maxPending()));
  }

  @Singleton
  @Requires(missingBeans = IndexStore.class)
  JdbcIndexStore jdbcIndexStore(
      BeanProvider<DataSource> dataSources,
      JdbcExecution execution,
      R2D1JdbcConfiguration configuration) {
    DataSource dataSource =
        BeanSelection.required(
            dataSources, configuration.datasource(), "DataSource", "r2d1.jdbc.datasource");
    return new JdbcIndexStore(dataSource, execution);
  }

  @Singleton
  @Requires(missingBeans = PersistenceCollectionFactory.CollectionInitializer.class)
  PersistenceCollectionFactory.CollectionInitializer jdbcCollectionInitializer(
      JdbcIndexStore indexStore) {
    return indexStore::initialize;
  }

  private static JdbcExecutionMode resolveExecutionMode(
      @org.jspecify.annotations.Nullable String configuredMode,
      @org.jspecify.annotations.Nullable R2D1JdbcExecutionMode boundMode) {
    if (configuredMode == null) {
      return boundMode == R2D1JdbcExecutionMode.VIRTUAL_THREAD
          ? JdbcExecutionMode.VIRTUAL_THREAD
          : JdbcExecutionMode.PLATFORM_THREAD;
    }
    return switch (configuredMode.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "platform-thread" -> JdbcExecutionMode.PLATFORM_THREAD;
      case "virtual-thread" -> JdbcExecutionMode.VIRTUAL_THREAD;
      default ->
          throw new ConfigurationException(
              "r2d1.jdbc.execution-mode must be platform-thread or virtual-thread: "
                  + configuredMode);
    };
  }
}
