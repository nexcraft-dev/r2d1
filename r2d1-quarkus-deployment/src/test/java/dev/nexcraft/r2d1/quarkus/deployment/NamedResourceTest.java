package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.quarkus.internal.BeanSelection;
import dev.nexcraft.r2d1.quarkus.internal.OwnedResources;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Explicit names select caller resources and managed wrapper shutdown leaves them usable. */
class NamedResourceTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .withAdditionalDependency(jar -> jar.addPackages(true, "dev.nexcraft.r2d1.jdbc"))
          .withApplicationRoot(
              root ->
                  root.addClasses(
                      OverrideIntegrationTest.class,
                      OverrideIntegrationTest.ApplicationFactory.class,
                      Resources.class,
                      OtherExecutor.class,
                      OtherDatasource.class,
                      TestSupport.class,
                      TestSupport.User.class,
                      TestSupport.Codec.class))
          .overrideConfigKey("quarkus.r2d1.enabled", "true")
          .overrideConfigKey("quarkus.r2d1.index.type", "jdbc")
          .overrideConfigKey("quarkus.r2d1.jdbc.datasource", "selected")
          .overrideConfigKey("quarkus.r2d1.jdbc.executor", "jdbc")
          .overrideConfigKey("quarkus.r2d1.jdbc.max-concurrency", "1")
          .overrideConfigKey("quarkus.r2d1.jdbc.max-pending", "0");

  @Test
  @org.junit.jupiter.api.Timeout(15)
  void selectsNamesAndPreservesBorrowedResources() throws Exception {
    final var beans = Arc.container().instance(BeanSelection.class).get();
    final var store = beans.required(IndexStore.class, Optional.empty());
    final var resources = Arc.container().instance(Resources.class).get();
    final var initializer = Arc.container().instance(OwnedResources.class).get().initializer(store);
    initializer
        .initialize(TestSupport.User.class)
        .toCompletableFuture()
        .get(5, java.util.concurrent.TimeUnit.SECONDS);
    final var release = new java.util.concurrent.CountDownLatch(1);
    resources.executor.submit(
        () -> {
          release.await();
          return null;
        });
    final var admitted = store.clear("quarkus_users").toCompletableFuture();
    try {
      assertThatThrownBy(() -> store.clear("quarkus_users").toCompletableFuture().join())
          .hasStackTraceContaining("capacity");
    } finally {
      release.countDown();
    }
    admitted.get(5, java.util.concurrent.TimeUnit.SECONDS);
    Arc.container().instance(OwnedResources.class).get().close();
    assertThat(resources.executor.submit(() -> "usable").get()).isEqualTo("usable");
    try (final var connection =
        beans.required(DataSource.class, Optional.of("selected")).getConnection()) {
      assertThat(connection.isClosed()).isFalse();
    }
    assertThatThrownBy(() -> store.clear("quarkus_users").toCompletableFuture().join())
        .hasStackTraceContaining("JDBC execution is closed");
  }

  /** Caller resources coexist with the default test datasource and executor. */
  @Singleton
  public static class Resources {
    final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Produces
    @Singleton
    @Named("jdbc")
    Executor executor() {
      return executor;
    }

    @Produces
    @Singleton
    @Named("selected")
    DataSource source() {
      final var source = new JdbcDataSource();
      source.setURL("jdbc:h2:mem:named;DB_CLOSE_DELAY=-1");
      return source;
    }

    @jakarta.annotation.PreDestroy
    void close() {
      executor.shutdown();
    }
  }

  /** An enabled alternative under another name must not hide an explicitly named executor. */
  @Singleton
  @jakarta.enterprise.inject.Alternative
  @jakarta.annotation.Priority(1)
  @Named("other")
  public static class OtherExecutor implements Executor {
    @Override
    public void execute(final Runnable command) {
      throw new AssertionError("The explicitly selected executor must win");
    }
  }

  /** Another enabled alternative cannot intercept the explicitly selected datasource. */
  @Singleton
  @jakarta.enterprise.inject.Alternative
  @jakarta.annotation.Priority(1)
  public static class OtherDatasource {
    /** Unselected producers must not be instantiated merely to inspect their names. */
    @Produces
    @jakarta.enterprise.inject.Alternative
    @Singleton
    @Named("other-source")
    DataSource source() {
      throw new AssertionError("The explicitly selected datasource must win");
    }
  }
}
