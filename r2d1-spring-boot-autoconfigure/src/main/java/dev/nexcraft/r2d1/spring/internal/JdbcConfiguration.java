package dev.nexcraft.r2d1.spring.internal;

import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.jdbc.JdbcExecution;
import dev.nexcraft.r2d1.jdbc.JdbcExecutionConfig;
import dev.nexcraft.r2d1.jdbc.JdbcExecutionMode;
import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spring.R2D1JdbcExecutionMode;
import dev.nexcraft.r2d1.spring.R2D1JdbcProperties;
import dev.nexcraft.r2d1.spring.R2D1Properties;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the optional JDBC index component over application-owned resources. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "dev.nexcraft.r2d1.jdbc.JdbcIndexStore")
@ConditionalOnProperty(prefix = "r2d1.index", name = "type", havingValue = "jdbc")
public class JdbcConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(JdbcExecution.class)
  JdbcExecution jdbcExecution(
      R2D1JdbcProperties properties,
      R2D1Properties globalProperties,
      ListableBeanFactory beanFactory,
      ObjectProvider<Executor> executors) {
    BackpressureConfig backpressure =
        BackpressureConfigurationSupport.resolveJdbc(
            properties.backpressure(),
            properties.legacyMaxConcurrency(),
            properties.legacyMaxPending(),
            globalProperties.backpressure());
    if (properties.executor() != null) {
      if (properties.executionMode() != null) {
        throw new IllegalStateException(
            "r2d1.jdbc.execution-mode cannot be configured with r2d1.jdbc.executor; the caller-owned executor defines its thread model");
      }
      Executor executor =
          BeanSelection.required(
              beanFactory,
              Executor.class,
              executors,
              properties.executor(),
              "Executor",
              "r2d1.jdbc.executor");
      return JdbcExecution.using(
          executor, backpressure.maxConcurrency(), backpressure.maxPending());
    }

    JdbcExecutionMode mode =
        properties.executionMode() == R2D1JdbcExecutionMode.VIRTUAL_THREAD
            ? JdbcExecutionMode.VIRTUAL_THREAD
            : JdbcExecutionMode.PLATFORM_THREAD;
    return JdbcExecution.create(
        new JdbcExecutionConfig(mode, backpressure.maxConcurrency(), backpressure.maxPending()));
  }

  @Bean
  @ConditionalOnMissingBean(IndexStore.class)
  JdbcIndexStore jdbcIndexStore(
      ListableBeanFactory beanFactory,
      ObjectProvider<DataSource> dataSources,
      JdbcExecution execution,
      R2D1JdbcProperties properties) {
    DataSource dataSource =
        BeanSelection.required(
            beanFactory,
            DataSource.class,
            dataSources,
            properties.datasource(),
            "DataSource",
            "r2d1.jdbc.datasource");
    return new JdbcIndexStore(dataSource, execution);
  }

  @Bean
  @ConditionalOnBean(JdbcIndexStore.class)
  @ConditionalOnMissingBean(PersistenceCollectionFactory.CollectionInitializer.class)
  PersistenceCollectionFactory.CollectionInitializer jdbcCollectionInitializer(
      JdbcIndexStore indexStore) {
    return indexStore::initialize;
  }
}
