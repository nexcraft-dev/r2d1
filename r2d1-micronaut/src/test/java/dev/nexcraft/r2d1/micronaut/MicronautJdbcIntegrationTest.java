package dev.nexcraft.r2d1.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.jdbc.JdbcExecution;
import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MicronautJdbcIntegrationTest {

  @TempDir Path temporaryDirectory;

  @Test
  void disabledIntegrationCreatesNoR2d1Bean() {
    try (ApplicationContext context = ApplicationContext.run()) {
      assertThat(context.containsBean(R2D1.class)).isFalse();
    }
  }

  @Test
  void createsR2d1OverH2AndPerformsPublicOperations() {
    JdbcDataSource dataSource = dataSource("jdbc:h2:mem:micronaut;DB_CLOSE_DELAY=-1");
    try (ApplicationContext context = jdbcContext(Map.of(), dataSource)) {
      assertPublicOperations(context.getBean(R2D1.class));
      assertThat(context.getBean(JdbcExecution.class)).isNotNull();
    }
  }

  @Test
  void supportsExplicitVirtualThreadExecution() {
    JdbcDataSource dataSource = dataSource("jdbc:h2:mem:micronautVirtual;DB_CLOSE_DELAY=-1");
    try (ApplicationContext context =
        jdbcContext(Map.of("r2d1.jdbc.execution-mode", "virtual-thread"), dataSource)) {
      assertPublicOperations(context.getBean(R2D1.class));
    }
  }

  @Test
  void closesManagedJdbcExecutionWithTheContext() {
    JdbcDataSource dataSource = dataSource("jdbc:h2:mem:managedClose;DB_CLOSE_DELAY=-1");
    ApplicationContext context = jdbcContext(Map.of(), dataSource);
    JdbcIndexStore indexStore = context.getBean(JdbcIndexStore.class);

    context.close();

    assertThatThrownBy(
            () ->
                indexStore.initialize(TestDocumentSupport.User.class).toCompletableFuture().join())
        .hasRootCauseMessage("JDBC execution is closed");
  }

  @Test
  void usesTheSameWiringForARemoteH2DataSource() throws Exception {
    Server server =
        Server.createTcpServer(
                "-tcpPort",
                "0",
                "-tcpDaemon",
                "-baseDir",
                temporaryDirectory.toString(),
                "-ifNotExists")
            .start();
    try {
      JdbcDataSource dataSource =
          dataSource("jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/./micronautRemote");
      try (ApplicationContext context = jdbcContext(Map.of(), dataSource)) {
        assertPublicOperations(context.getBean(R2D1.class));
      }
    } finally {
      server.stop();
    }
  }

  @Test
  void selectsAnExplicitlyNamedDataSource() throws Exception {
    JdbcDataSource first = dataSource("jdbc:h2:mem:first;DB_CLOSE_DELAY=-1");
    JdbcDataSource second = dataSource("jdbc:h2:mem:second;DB_CLOSE_DELAY=-1");
    Map<String, Object> properties = Map.of("r2d1.jdbc.datasource", "second");

    try (ApplicationContext context =
        jdbcContext(
            properties,
            namedBean(DataSource.class, "first", first),
            namedBean(DataSource.class, "second", second))) {
      context.getBean(R2D1.class).collection(TestDocumentSupport.User.class);
      assertThat(hasUsersTable(first)).isFalse();
      assertThat(hasUsersTable(second)).isTrue();
    }
  }

  @Test
  void rejectsAmbiguousDataSourcesWithoutASelection() {
    JdbcDataSource first = dataSource("jdbc:h2:mem:ambiguousFirst;DB_CLOSE_DELAY=-1");
    JdbcDataSource second = dataSource("jdbc:h2:mem:ambiguousSecond;DB_CLOSE_DELAY=-1");

    assertThatThrownBy(
            () ->
                jdbcContext(
                    Map.of(),
                    namedBean(DataSource.class, "first", first),
                    namedBean(DataSource.class, "second", second)))
        .hasMessageContaining("Multiple DataSource beans")
        .hasMessageContaining("r2d1.jdbc.datasource");
  }

  @Test
  void rejectsAMissingNamedDataSource() {
    JdbcDataSource available = dataSource("jdbc:h2:mem:available;DB_CLOSE_DELAY=-1");

    assertThatThrownBy(
            () ->
                jdbcContext(
                    Map.of("r2d1.jdbc.datasource", "missing"),
                    namedBean(DataSource.class, "available", available)))
        .hasMessageContaining("No DataSource bean named 'missing'")
        .hasMessageContaining("r2d1.jdbc.datasource");
  }

  @Test
  void selectsAPrimaryDataSourceWhenSeveralExist() throws Exception {
    try (ApplicationContext context = jdbcContext(Map.of("test.primary-datasources", true))) {
      context.getBean(R2D1.class).collection(TestDocumentSupport.User.class);
      DataSource primary = context.getBean(DataSource.class, Qualifiers.byName("primary"));
      DataSource secondary = context.getBean(DataSource.class, Qualifiers.byName("secondary"));
      assertThat(hasUsersTable(primary)).isTrue();
      assertThat(hasUsersTable(secondary)).isFalse();
    }
  }

  @Test
  void borrowsAnExplicitlyNamedExecutor() {
    JdbcDataSource dataSource = dataSource("jdbc:h2:mem:externalExecutor;DB_CLOSE_DELAY=-1");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      try (ApplicationContext context =
          jdbcContext(
              Map.of("r2d1.jdbc.executor", "r2d1-worker"),
              bean(DataSource.class, dataSource),
              namedBean(Executor.class, "r2d1-worker", executor))) {
        assertPublicOperations(context.getBean(R2D1.class));
      }
      assertThat(executor.isShutdown()).isFalse();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsExecutionModeWhenCallerProvidesExecutor() {
    JdbcDataSource dataSource = dataSource("jdbc:h2:mem:invalidExecutor;DB_CLOSE_DELAY=-1");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertThatThrownBy(
              () ->
                  jdbcContext(
                      Map.of(
                          "r2d1.jdbc.executor",
                          "r2d1-worker",
                          "r2d1.jdbc.execution-mode",
                          "platform-thread"),
                      bean(DataSource.class, dataSource),
                      namedBean(Executor.class, "r2d1-worker", executor)))
          .hasMessageContaining("r2d1.jdbc.execution-mode cannot be configured");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsAMissingNamedExecutor() {
    JdbcDataSource dataSource = dataSource("jdbc:h2:mem:missingExecutor;DB_CLOSE_DELAY=-1");

    assertThatThrownBy(
            () ->
                jdbcContext(
                    Map.of("r2d1.jdbc.executor", "missing"), bean(DataSource.class, dataSource)))
        .hasMessageContaining("No Executor bean named 'missing'")
        .hasMessageContaining("r2d1.jdbc.executor");
  }

  @Test
  void rejectsAnUnknownExecutionMode() {
    JdbcDataSource dataSource = dataSource("jdbc:h2:mem:unknownMode;DB_CLOSE_DELAY=-1");

    assertThatThrownBy(
            () ->
                jdbcContext(
                    Map.of("r2d1.jdbc.execution-mode", "automatic"),
                    bean(DataSource.class, dataSource)))
        .hasMessageContaining("r2d1.jdbc.execution-mode")
        .hasMessageContaining("automatic");
  }

  @Test
  void userProvidedR2d1BeanWinsWhileIntegrationIsEnabled() {
    R2D1 applicationBean =
        R2D1.builder()
            .collectionFactory(
                new R2D1.CollectionFactory() {
                  @Override
                  public <T> R2D1Collection<T> create(Class<T> documentType) {
                    throw new UnsupportedOperationException(documentType.getName());
                  }
                })
            .build();
    AtomicInteger dataSourceCreations = new AtomicInteger();
    RuntimeBeanDefinition<DataSource> dataSourceDefinition =
        RuntimeBeanDefinition.builder(
                DataSource.class,
                () -> {
                  dataSourceCreations.incrementAndGet();
                  return dataSource("jdbc:h2:mem:unused;DB_CLOSE_DELAY=-1");
                })
            .build();
    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(Map.of("r2d1.enabled", true, "r2d1.index.type", "jdbc"))
            .singletons(applicationBean)
            .beanDefinitions(dataSourceDefinition)
            .start()) {
      assertThat(context.getBean(R2D1.class)).isSameAs(applicationBean);
      assertThat(dataSourceCreations).hasValue(0);
    }
    assertThat(dataSourceCreations).hasValue(0);
  }

  @Test
  void missingIndexSelectionFailsDuringContextStartup() {
    assertThatThrownBy(
            () ->
                ApplicationContext.builder()
                    .properties(Map.of("r2d1.enabled", true))
                    .singletons(
                        new TestDocumentSupport.MemoryDocumentStore(),
                        new TestDocumentSupport.UserCodec())
                    .start())
        .hasMessageContaining("r2d1.index.type must be jdbc or d1");
  }

  @Test
  void unknownIndexSelectionFailsDuringContextStartup() {
    assertThatThrownBy(
            () ->
                ApplicationContext.builder()
                    .properties(Map.of("r2d1.enabled", true, "r2d1.index.type", "automatic"))
                    .singletons(
                        new TestDocumentSupport.MemoryDocumentStore(),
                        new TestDocumentSupport.UserCodec())
                    .start())
        .hasMessageContaining("r2d1.index.type must be jdbc or d1")
        .hasMessageContaining("automatic");
  }

  private static ApplicationContext jdbcContext(
      Map<String, Object> additionalProperties, Object... beans) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("r2d1.enabled", true);
    properties.put("r2d1.index.type", "jdbc");
    properties.putAll(additionalProperties);
    return ApplicationContext.builder()
        .properties(properties)
        .singletons(
            new TestDocumentSupport.MemoryDocumentStore(), new TestDocumentSupport.UserCodec())
        .beanDefinitions(runtimeDefinitions(beans))
        .start();
  }

  private static ApplicationContext jdbcContext(
      Map<String, Object> additionalProperties, DataSource dataSource) {
    return jdbcContext(additionalProperties, bean(DataSource.class, dataSource));
  }

  private static RuntimeBeanDefinition<?>[] runtimeDefinitions(Object... definitions) {
    RuntimeBeanDefinition<?>[] result = new RuntimeBeanDefinition<?>[definitions.length];
    for (int index = 0; index < definitions.length; index++) {
      Object definition = definitions[index];
      result[index] =
          definition instanceof RuntimeBeanDefinition<?> runtimeDefinition
              ? runtimeDefinition
              : RuntimeBeanDefinition.of(definition);
    }
    return result;
  }

  private static <T> RuntimeBeanDefinition<T> namedBean(Class<T> type, String name, T instance) {
    return RuntimeBeanDefinition.builder(type, () -> instance).named(name).build();
  }

  private static <T> RuntimeBeanDefinition<T> bean(Class<T> type, T instance) {
    return RuntimeBeanDefinition.builder(type, () -> instance).build();
  }

  private static JdbcDataSource dataSource(String url) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(url);
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private static boolean hasUsersTable(DataSource dataSource) throws Exception {
    try (var connection = dataSource.getConnection();
        var result =
            connection.getMetaData().getTables(null, "PUBLIC", "users", new String[] {"TABLE"})) {
      return result.next();
    }
  }

  private static void assertPublicOperations(R2D1 r2d1) {
    R2D1Collection<TestDocumentSupport.User> users =
        r2d1.collection(TestDocumentSupport.User.class);
    TestDocumentSupport.User user = new TestDocumentSupport.User("user-1", "NZ", "Aroha");

    users.put(user);
    assertThat(users.get(user.id())).contains(user);
    assertThat(
            users
                .query()
                .where("country")
                .eq("NZ")
                .sortBy("country", SortDirection.ASC)
                .limit(10)
                .fetch()
                .items())
        .containsExactly(user);
    users.delete(user.id());
    assertThat(users.get(user.id())).isEmpty();
  }

  @Factory
  @Requires(property = "test.primary-datasources", value = "true")
  static final class PrimaryDataSourceFactory {

    @Singleton
    @Primary
    @Named("primary")
    DataSource primary() {
      return dataSource("jdbc:h2:mem:primary;DB_CLOSE_DELAY=-1");
    }

    @Singleton
    @Named("secondary")
    DataSource secondary() {
      return dataSource("jdbc:h2:mem:secondary;DB_CLOSE_DELAY=-1");
    }
  }
}
