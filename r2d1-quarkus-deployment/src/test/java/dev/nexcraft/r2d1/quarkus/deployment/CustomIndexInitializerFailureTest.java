package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.quarkus.internal.OwnedResources;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Application stores bypass backend configuration and require an explicit matching initializer. */
class CustomIndexInitializerFailureTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .withApplicationRoot(
              root ->
                  root.addClasses(
                      Stores.class,
                      TestSupport.class,
                      TestSupport.User.class,
                      TestSupport.Codec.class))
          .overrideConfigKey("quarkus.r2d1.enabled", "true")
          .assertException(
              failure ->
                  assertThat(failure)
                      .hasStackTraceContaining("matching CollectionInitializer")
                      .hasStackTraceContaining("Test-owned document store cleanup observed"));

  @Test
  void checksApplicationWiring() {}

  /** Owned test store cleanup must run when later initializer validation fails. */
  @Singleton
  public static class Stores {
    @Produces
    @Singleton
    DocumentStore documents(final OwnedResources resources) {
      final var store =
          (DocumentStore)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {DocumentStore.class, AutoCloseable.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                      throw new IllegalStateException("Test-owned document store cleanup observed");
                    }
                    throw new AssertionError("Unexpected storage call " + method.getName());
                  });
      resources.own((AutoCloseable) store);
      return store;
    }

    @Produces
    @Singleton
    IndexStore indexes() {
      return stub(IndexStore.class);
    }

    private <T> T stub(final Class<T> type) {
      return type.cast(
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {type},
              (proxy, method, args) -> {
                throw new AssertionError("Unexpected storage call " + method.getName());
              }));
    }
  }
}
