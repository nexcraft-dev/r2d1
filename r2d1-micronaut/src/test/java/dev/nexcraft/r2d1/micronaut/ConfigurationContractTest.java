package dev.nexcraft.r2d1.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
import dev.nexcraft.r2d1.r2.R2DocumentStore;
import dev.nexcraft.r2d1.spi.AdmissionRejectedException;
import dev.nexcraft.r2d1.spi.DocumentStore;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class ConfigurationContractTest {

  @Test
  void secretConfigurationStringsAreRedacted() {
    R2D1R2Configuration r2 =
        new R2D1R2Configuration(
            URI.create("https://private.example.com"),
            "access-value",
            "secret-value",
            "bucket-value",
            "auto");
    R2D1D1Configuration d1 =
        new R2D1D1Configuration("account-value", "database-value", "token-value");

    assertThat(r2.toString())
        .doesNotContain("private.example.com", "access-value", "secret-value", "bucket-value")
        .contains("<redacted>", "region=auto");
    assertThat(d1.toString())
        .doesNotContain("account-value", "database-value", "token-value")
        .contains("<redacted>");
  }

  @Test
  void generatedMetadataContainsTheSupportedProperties() throws Exception {
    String metadata;
    try (var input =
        getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
      assertThat(input).isNotNull();
      metadata = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    assertThat(metadata)
        .contains(
            "r2d1.enabled",
            "r2d1.index.type",
            "r2d1.jdbc.datasource",
            "r2d1.jdbc.executor",
            "r2d1.jdbc.execution-mode",
            "r2d1.jdbc.max-concurrency",
            "r2d1.jdbc.max-pending",
            "r2d1.backpressure.max-concurrency",
            "r2d1.backpressure.max-pending",
            "r2d1.r2.backpressure.max-concurrency",
            "r2d1.r2.backpressure.max-pending",
            "r2d1.r2.client.max-concurrency",
            "r2d1.d1.backpressure.max-concurrency",
            "r2d1.d1.backpressure.max-pending",
            "r2d1.jdbc.backpressure.max-concurrency",
            "r2d1.jdbc.backpressure.max-pending",
            "r2d1.r2.endpoint",
            "r2d1.r2.access-key-id",
            "r2d1.r2.secret-access-key",
            "r2d1.r2.bucket-name",
            "r2d1.r2.region",
            "r2d1.d1.account-id",
            "r2d1.d1.database-id",
            "r2d1.d1.api-token");
  }

  @Test
  void bindsGlobalAdapterAndS3ClientBackpressureProperties() {
    Map<String, Object> properties =
        Map.ofEntries(
            Map.entry("r2d1.enabled", true),
            Map.entry("r2d1.index.type", "jdbc"),
            Map.entry("r2d1.backpressure.max-concurrency", 10),
            Map.entry("r2d1.backpressure.max-pending", 20),
            Map.entry("r2d1.r2.backpressure.max-concurrency", 4),
            Map.entry("r2d1.r2.backpressure.max-pending", 5),
            Map.entry("r2d1.r2.client.max-concurrency", 3),
            Map.entry("r2d1.d1.backpressure.max-concurrency", 6),
            Map.entry("r2d1.jdbc.backpressure.max-concurrency", 2),
            Map.entry("r2d1.jdbc.backpressure.max-pending", 7),
            Map.entry("r2d1.jdbc.max-concurrency", 9),
            Map.entry("r2d1.jdbc.max-pending", 13));

    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(properties)
            .singletons(
                new TestDocumentSupport.MemoryDocumentStore(),
                new TestDocumentSupport.UserCodec(),
                dataSource())
            .start()) {
      assertThat(context.getBean(R2D1Configuration.class).backpressure())
          .isEqualTo(new R2D1Configuration.BackpressureConfiguration(10, 20));
      assertThat(context.getBean(R2D1R2Configuration.class).backpressure())
          .isEqualTo(new R2D1R2Configuration.BackpressureConfiguration(4, 5));
      assertThat(context.getBean(R2D1R2Configuration.class).client())
          .isEqualTo(new R2D1R2Configuration.ClientConfiguration(3));
      assertThat(context.getBean(R2D1D1Configuration.class).backpressure())
          .isEqualTo(new R2D1D1Configuration.BackpressureConfiguration(6, null));
      R2D1JdbcConfiguration jdbc = context.getBean(R2D1JdbcConfiguration.class);
      assertThat(jdbc.backpressure())
          .isEqualTo(new R2D1JdbcConfiguration.BackpressureConfiguration(2, 7));
      assertThat(jdbc.maxConcurrency()).isEqualTo(9);
      assertThat(jdbc.maxPending()).isEqualTo(13);
    }
  }

  @Test
  void keepsAbsentValuesInheritableAndPreservesPreviousConstructors() {
    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(Map.of("r2d1.enabled", true, "r2d1.index.type", "jdbc"))
            .singletons(
                new TestDocumentSupport.MemoryDocumentStore(),
                new TestDocumentSupport.UserCodec(),
                dataSource())
            .start()) {
      assertThat(context.getBean(R2D1Configuration.class).backpressure().maxConcurrency()).isNull();
      assertThat(context.getBean(R2D1Configuration.class).backpressure().maxPending()).isNull();
      assertThat(context.getBean(R2D1R2Configuration.class).backpressure().maxConcurrency())
          .isNull();
      assertThat(context.getBean(R2D1R2Configuration.class).backpressure().maxPending()).isNull();
      assertThat(context.getBean(R2D1R2Configuration.class).client().maxConcurrency()).isNull();
      assertThat(context.getBean(R2D1D1Configuration.class).backpressure().maxConcurrency())
          .isNull();
      assertThat(context.getBean(R2D1D1Configuration.class).backpressure().maxPending()).isNull();
      R2D1JdbcConfiguration jdbc = context.getBean(R2D1JdbcConfiguration.class);
      assertThat(jdbc.backpressure().maxConcurrency()).isNull();
      assertThat(jdbc.backpressure().maxPending()).isNull();
      assertThat(jdbc.maxConcurrency()).isEqualTo(4);
      assertThat(jdbc.maxPending()).isEqualTo(64);
      assertThat(context.getEnvironment().containsProperty("r2d1.jdbc.max-concurrency")).isFalse();
      assertThat(context.getEnvironment().containsProperty("r2d1.jdbc.max-pending")).isFalse();
    }

    assertThat(new R2D1Configuration(true).backpressure()).isNull();
    assertThat(new R2D1R2Configuration(null, null, null, null, "auto").client()).isNull();
    assertThat(new R2D1D1Configuration(null, null, null).backpressure()).isNull();
    assertThat(new R2D1JdbcConfiguration(null, null, null, 4, 64).maxConcurrency()).isEqualTo(4);
  }

  @Test
  void resolvesLegacyAndNestedJdbcLimitsBeforeGlobalValues() {
    assertJdbcAdmission(
        Map.ofEntries(
            Map.entry("r2d1.enabled", true),
            Map.entry("r2d1.index.type", "jdbc"),
            Map.entry("r2d1.jdbc.datasource", "applicationDataSource"),
            Map.entry("r2d1.jdbc.executor", "sharedExecutor"),
            Map.entry("r2d1.backpressure.max-concurrency", 1),
            Map.entry("r2d1.backpressure.max-pending", 0),
            Map.entry("r2d1.jdbc.max-concurrency", 2)));

    assertJdbcAdmission(
        Map.ofEntries(
            Map.entry("r2d1.enabled", true),
            Map.entry("r2d1.index.type", "jdbc"),
            Map.entry("r2d1.jdbc.datasource", "applicationDataSource"),
            Map.entry("r2d1.jdbc.executor", "sharedExecutor"),
            Map.entry("r2d1.backpressure.max-concurrency", 1),
            Map.entry("r2d1.backpressure.max-pending", 0),
            Map.entry("r2d1.jdbc.max-concurrency", 3),
            Map.entry("r2d1.jdbc.backpressure.max-concurrency", 2)));
  }

  @Test
  void ownedR2ClientUsesConfiguredCapacityAndWarnsBelowAdmission() {
    Logger logger = Logger.getLogger("dev.nexcraft.r2d1.r2.R2ClientFactory");
    CapturingHandler handler = new CapturingHandler();
    logger.addHandler(handler);
    try {
      Map<String, Object> properties =
          Map.ofEntries(
              Map.entry("r2d1.enabled", true),
              Map.entry("r2d1.index.type", "jdbc"),
              Map.entry("r2d1.r2.endpoint", "https://account.r2.cloudflarestorage.com"),
              Map.entry("r2d1.r2.access-key-id", "access-key"),
              Map.entry("r2d1.r2.secret-access-key", "secret-key"),
              Map.entry("r2d1.r2.bucket-name", "documents"),
              Map.entry("r2d1.backpressure.max-concurrency", 8),
              Map.entry("r2d1.r2.client.max-concurrency", 2));

      try (ApplicationContext context =
          ApplicationContext.builder()
              .properties(properties)
              .singletons(new TestDocumentSupport.UserCodec())
              .beanDefinitions(
                  namedBean(DataSource.class, "applicationDataSource", dataSource("r2-warning")))
              .start()) {
        assertThat(context.getBean(DocumentStore.class)).isInstanceOf(R2DocumentStore.class);
      }

      assertThat(handler.messages())
          .anySatisfy(
              message ->
                  assertThat(message)
                      .contains("below R2 admission maxConcurrency 8")
                      .contains("2"));
    } finally {
      logger.removeHandler(handler);
    }
  }

  private static void assertJdbcAdmission(Map<String, Object> properties) {
    ManualExecutor executor = new ManualExecutor();
    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(properties)
            .singletons(
                new TestDocumentSupport.MemoryDocumentStore(), new TestDocumentSupport.UserCodec())
            .beanDefinitions(
                namedBean(DataSource.class, "applicationDataSource", dataSource("jdbc-admission")),
                namedBean(Executor.class, "sharedExecutor", executor))
            .start()) {
      JdbcIndexStore jdbc = context.getBean(JdbcIndexStore.class);
      CompletableFuture<?> first = jdbc.initialize(AdmissionFirst.class).toCompletableFuture();
      CompletableFuture<?> second = jdbc.initialize(AdmissionSecond.class).toCompletableFuture();

      assertThatThrownBy(() -> jdbc.initialize(AdmissionThird.class).toCompletableFuture().join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(AdmissionRejectedException.class);
      assertThat(executor.pendingTasks()).isEqualTo(2);
      assertThat(first).isNotCompleted();
      assertThat(second).isNotCompleted();

      executor.runAll();

      assertThat(first).isCompleted();
      assertThat(second).isCompleted();
    }
  }

  private static <T> RuntimeBeanDefinition<T> namedBean(Class<T> type, String name, T instance) {
    return RuntimeBeanDefinition.builder(type, () -> instance).named(name).build();
  }

  private static DataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:configuration-contract");
    return dataSource;
  }

  private static DataSource dataSource(String name) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + name);
    return dataSource;
  }

  @Document("micronaut_admission_first")
  private static final class AdmissionFirst {
    @Index private String name;
  }

  @Document("micronaut_admission_second")
  private static final class AdmissionSecond {
    @Index private String name;
  }

  @Document("micronaut_admission_third")
  private static final class AdmissionThird {
    @Index private String name;
  }

  private static final class ManualExecutor implements Executor {

    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable task) {
      tasks.add(task);
    }

    private synchronized int pendingTasks() {
      return tasks.size();
    }

    private void runAll() {
      while (true) {
        Runnable task;
        synchronized (this) {
          task = tasks.poll();
        }
        if (task == null) {
          return;
        }
        task.run();
      }
    }
  }

  private static final class CapturingHandler extends Handler {

    private final List<String> messages = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      messages.add(record.getMessage());
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    private List<String> messages() {
      return List.copyOf(messages);
    }
  }
}
