package dev.nexcraft.r2d1.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** ApplicationContextRunner coverage for Spring activation, binding, conditions, and ownership. */
class SpringAutoConfigurationTest {

  @Test
  void isDisabledByDefault() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(R2D1.class);
              assertThat(context).doesNotHaveBean(R2D1Properties.class);
            });
  }

  @Test
  void activatesWithAnApplicationCollectionFactory() {
    R2D1.CollectionFactory factory = collectionFactory();

    runner()
        .withPropertyValues("r2d1.enabled=true")
        .withBean(R2D1.CollectionFactory.class, () -> factory)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(R2D1.class);
              assertThat(context.getBean(R2D1.CollectionFactory.class)).isSameAs(factory);
              assertThat(context.getBean(R2D1Properties.class).enabled()).isTrue();
            });
  }

  @Test
  void backsOffForAnApplicationR2D1Bean() {
    R2D1 applicationR2D1 = R2D1.builder().collectionFactory(collectionFactory()).build();

    runner()
        .withPropertyValues("r2d1.enabled=true")
        .withBean(R2D1.class, () -> applicationR2D1)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(R2D1.class)).isSameAs(applicationR2D1);
              assertThat(context).doesNotHaveBean(PersistenceCollectionFactory.class);
            });
  }

  @Test
  void bindsPublicPropertiesAndGeneratesMetadata() {
    runner()
        .withPropertyValues(
            "r2d1.enabled=true",
            "r2d1.document.type=filesystem",
            "r2d1.filesystem.root-directory=/var/lib/r2d1",
            "r2d1.filesystem.executor=filesystemExecutor",
            "r2d1.index.type=jdbc",
            "r2d1.jdbc.datasource=applicationDataSource",
            "r2d1.jdbc.executor=jdbcExecutor",
            "r2d1.jdbc.max-concurrency=7",
            "r2d1.jdbc.max-pending=19",
            "r2d1.r2.endpoint=https://example.r2.cloudflarestorage.com",
            "r2d1.r2.access-key-id=key-value",
            "r2d1.r2.secret-access-key=secret-value",
            "r2d1.r2.bucket-name=bucket-value",
            "r2d1.r2.region=auto",
            "r2d1.d1.account-id=account-value",
            "r2d1.d1.database-id=database-value",
            "r2d1.d1.api-token=token-value")
        .withBean(R2D1.CollectionFactory.class, SpringAutoConfigurationTest::collectionFactory)
        .withBean("filesystemExecutor", Executor.class, () -> (Executor) Runnable::run)
        .withBean("jdbcExecutor", Executor.class, () -> (Executor) Runnable::run)
        .withBean("applicationDataSource", DataSource.class, () -> dataSource("binding"))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(R2D1DocumentProperties.class).type())
                  .isEqualTo(DocumentBackend.FILESYSTEM);
              assertThat(context.getBean(R2D1FilesystemProperties.class).rootDirectory())
                  .isEqualTo(Path.of("/var/lib/r2d1"));
              assertThat(context.getBean(R2D1JdbcProperties.class).maxConcurrency()).isEqualTo(7);
              assertThat(context.getBean(R2D1JdbcProperties.class).maxPending()).isEqualTo(19);
              assertThat(context.getBean(R2D1JdbcProperties.class).executionMode()).isNull();
              assertThat(context.getBean(R2D1R2Properties.class).toString())
                  .doesNotContain("key-value", "secret-value", "bucket-value", "example.r2");
              assertThat(context.getBean(R2D1D1Properties.class).toString())
                  .doesNotContain("account-value", "database-value", "token-value");
              try (var metadata =
                  getClass().getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
                assertThat(metadata).isNotNull();
                String json = new String(metadata.readAllBytes());
                assertThat(json)
                    .contains(
                        "r2d1.enabled",
                        "r2d1.document.type",
                        "r2d1.filesystem.root-directory",
                        "r2d1.index.type",
                        "r2d1.jdbc.max-concurrency",
                        "r2d1.r2.endpoint",
                        "r2d1.d1.api-token");
              } catch (Exception failure) {
                throw new AssertionError("Could not read Spring configuration metadata", failure);
              }
            });
  }

  @Test
  void createsAllFourStorageCombinations() throws Exception {
    Path root = Files.createTempDirectory("r2d1-spring-");
    try {
      assertCombination("r2", "d1", root, false);
      assertCombination("r2", "jdbc", root, false);
      assertCombination("filesystem", "d1", root, true);
      assertCombination("filesystem", "jdbc", root, true);
    } finally {
      Files.walk(root)
          .sorted((left, right) -> right.compareTo(left))
          .forEach(path -> path.toFile().delete());
    }
  }

  @Test
  void selectsNamedAndPrimaryDataSources() {
    DataSource first = dataSource("springFirst");
    DataSource second = dataSource("springSecond");
    runner()
        .withPropertyValues(
            "r2d1.enabled=true", "r2d1.index.type=jdbc", "r2d1.jdbc.datasource=second")
        .withBean(DocumentCodec.class, SpringAutoConfigurationTest::codec)
        .withBean(DocumentStore.class, SpringAutoConfigurationTest::documentStore)
        .withBean(
            PersistenceCollectionFactory.CollectionInitializer.class,
            SpringAutoConfigurationTest::initializer)
        .withBean("first", DataSource.class, () -> first)
        .withBean("second", DataSource.class, () -> second)
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(R2D1.class));

    runner()
        .withPropertyValues("r2d1.enabled=true", "r2d1.index.type=jdbc")
        .withBean(DocumentCodec.class, SpringAutoConfigurationTest::codec)
        .withBean(DocumentStore.class, SpringAutoConfigurationTest::documentStore)
        .withBean(
            PersistenceCollectionFactory.CollectionInitializer.class,
            SpringAutoConfigurationTest::initializer)
        .withBean(
            "primary", DataSource.class, () -> first, definition -> definition.setPrimary(true))
        .withBean("secondary", DataSource.class, () -> second)
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(R2D1.class));
  }

  @Test
  void rejectsAmbiguousOrMissingDataSources() {
    runner()
        .withPropertyValues("r2d1.enabled=true", "r2d1.index.type=jdbc")
        .withBean(DocumentCodec.class, SpringAutoConfigurationTest::codec)
        .withBean(DocumentStore.class, SpringAutoConfigurationTest::documentStore)
        .withBean(
            PersistenceCollectionFactory.CollectionInitializer.class,
            SpringAutoConfigurationTest::initializer)
        .withBean("first", DataSource.class, () -> dataSource("ambiguousFirst"))
        .withBean("second", DataSource.class, () -> dataSource("ambiguousSecond"))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("Multiple DataSource beans"));

    runner()
        .withPropertyValues(
            "r2d1.enabled=true", "r2d1.index.type=jdbc", "r2d1.jdbc.datasource=missing")
        .withBean(DocumentCodec.class, SpringAutoConfigurationTest::codec)
        .withBean(DocumentStore.class, SpringAutoConfigurationTest::documentStore)
        .withBean(
            PersistenceCollectionFactory.CollectionInitializer.class,
            SpringAutoConfigurationTest::initializer)
        .withBean(DataSource.class, () -> dataSource("available"))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("No DataSource bean named 'missing'"));
  }

  @Test
  void doesNotCloseApplicationExecutorOrS3Client() {
    AtomicBoolean s3Closed = new AtomicBoolean();
    Object s3Client =
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {software.amazon.awssdk.services.s3.S3AsyncClient.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("close")) {
                s3Closed.set(true);
                return null;
              }
              if (method.getName().equals("toString")) {
                return "application-s3-client";
              }
              if (method.getReturnType() == boolean.class) {
                return false;
              }
              if (method.getReturnType() == int.class) {
                return 0;
              }
              if (method.getReturnType() == long.class) {
                return 0L;
              }
              return null;
            });

    runner()
        .withPropertyValues(
            "r2d1.enabled=true", "r2d1.document.type=r2", "r2d1.r2.bucket-name=application-bucket")
        .withBean(R2D1.CollectionFactory.class, SpringAutoConfigurationTest::collectionFactory)
        .withBean(
            software.amazon.awssdk.services.s3.S3AsyncClient.class,
            () -> (software.amazon.awssdk.services.s3.S3AsyncClient) s3Client,
            definition -> definition.setDestroyMethodName(""))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              context.close();
              assertThat(s3Closed).isFalse();
            });
  }

  @Test
  void doesNotCloseAnApplicationExecutor() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      runner()
          .withPropertyValues(
              "r2d1.enabled=true", "r2d1.index.type=jdbc", "r2d1.jdbc.executor=applicationExecutor")
          .withBean(DocumentCodec.class, SpringAutoConfigurationTest::codec)
          .withBean(DocumentStore.class, SpringAutoConfigurationTest::documentStore)
          .withBean(
              PersistenceCollectionFactory.CollectionInitializer.class,
              SpringAutoConfigurationTest::initializer)
          .withBean(DataSource.class, () -> dataSource("external-executor"))
          .withBean(
              "applicationExecutor",
              Executor.class,
              () -> executor,
              definition -> definition.setDestroyMethodName(""))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                context.close();
                assertThat(executor.isShutdown()).isFalse();
              });
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsExecutionModeTogetherWithAnExternalExecutor() {
    Executor executor = Runnable::run;
    runner()
        .withPropertyValues(
            "r2d1.enabled=true",
            "r2d1.index.type=jdbc",
            "r2d1.jdbc.executor=applicationExecutor",
            "r2d1.jdbc.execution-mode=platform-thread")
        .withBean(DocumentCodec.class, SpringAutoConfigurationTest::codec)
        .withBean(DocumentStore.class, SpringAutoConfigurationTest::documentStore)
        .withBean(
            PersistenceCollectionFactory.CollectionInitializer.class,
            SpringAutoConfigurationTest::initializer)
        .withBean(DataSource.class, () -> dataSource("modeConflict"))
        .withBean("applicationExecutor", Executor.class, () -> executor)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("execution-mode cannot be configured"));
  }

  @Test
  void supportsExplicitVirtualThreadExecutionOnJava25OrLater() {
    Assumptions.assumeTrue(Runtime.version().feature() >= 25);
    runner()
        .withPropertyValues(
            "r2d1.enabled=true", "r2d1.index.type=jdbc", "r2d1.jdbc.execution-mode=virtual-thread")
        .withBean(DocumentCodec.class, SpringAutoConfigurationTest::codec)
        .withBean(DocumentStore.class, SpringAutoConfigurationTest::documentStore)
        .withBean(DataSource.class, () -> dataSource("virtual-thread"))
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(R2D1.class));
  }

  @Test
  void rejectsInvalidBackendAndLimitsDuringBinding() {
    runner()
        .withPropertyValues("r2d1.enabled=true", "r2d1.document.type=unknown")
        .withBean(R2D1.CollectionFactory.class, SpringAutoConfigurationTest::collectionFactory)
        .run(context -> assertThat(context).hasFailed());

    runner()
        .withPropertyValues("r2d1.enabled=true", "r2d1.jdbc.max-concurrency=0")
        .withBean(R2D1.CollectionFactory.class, SpringAutoConfigurationTest::collectionFactory)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage("r2d1.jdbc.max-concurrency must be greater than zero"));
  }

  private static ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(R2D1AutoConfiguration.class));
  }

  private static void assertCombination(
      String documentBackend, String indexBackend, Path root, boolean filesystem) {
    ApplicationContextRunner combination =
        runner()
            .withPropertyValues(
                "r2d1.enabled=true",
                "r2d1.document.type=" + documentBackend,
                "r2d1.index.type=" + indexBackend,
                "r2d1.r2.endpoint=https://example.r2.cloudflarestorage.com",
                "r2d1.r2.access-key-id=key",
                "r2d1.r2.secret-access-key=secret",
                "r2d1.r2.bucket-name=spring-test-bucket",
                "r2d1.d1.account-id=account",
                "r2d1.d1.database-id=database",
                "r2d1.d1.api-token=token")
            .withBean(DocumentCodec.class, SpringAutoConfigurationTest::codec);
    if (filesystem) {
      combination = combination.withBean(Executor.class, () -> (Executor) Runnable::run);
      combination =
          combination.withPropertyValues("r2d1.filesystem.root-directory=" + root.toString());
    }
    if (indexBackend.equals("jdbc")) {
      combination =
          combination.withBean(
              DataSource.class, () -> dataSource("combination-" + documentBackend));
    }
    combination.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(R2D1.class);
          assertThat(context).hasSingleBean(DocumentStore.class);
          assertThat(context).hasSingleBean(IndexStore.class);
        });
  }

  private static R2D1.CollectionFactory collectionFactory() {
    return new R2D1.CollectionFactory() {
      @Override
      public <T> R2D1Collection<T> create(Class<T> documentType) {
        throw new UnsupportedOperationException("test collection factory");
      }
    };
  }

  private static DocumentCodec codec() {
    return new DocumentCodec() {
      @Override
      public StoredDocument serialize(Object document) {
        return new StoredDocument(new byte[0]);
      }

      @Override
      public <T> T deserialize(StoredDocument document, Class<T> documentType) {
        throw new UnsupportedOperationException("test codec");
      }
    };
  }

  private static DocumentStore documentStore() {
    return (DocumentStore)
        Proxy.newProxyInstance(
            SpringAutoConfigurationTest.class.getClassLoader(),
            new Class<?>[] {DocumentStore.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("list")) {
                return CompletableFuture.completedFuture(
                    new DocumentPage(List.of(), java.util.Optional.empty()));
              }
              return CompletableFuture.completedFuture(null);
            });
  }

  private static PersistenceCollectionFactory.CollectionInitializer initializer() {
    return ignored -> CompletableFuture.completedFuture(null);
  }

  private static JdbcDataSource dataSource(String name) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    return dataSource;
  }
}
