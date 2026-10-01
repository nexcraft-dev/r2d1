package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Application stores bypass backend configuration and require an explicit matching initializer. */
class CustomStoresTest {
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
          .overrideConfigKey("quarkus.r2d1.enabled", "true");

  @Test
  void checksApplicationWiring() {
    assertThat(Arc.container().instance(R2D1.class).isAvailable()).isTrue();
  }

  /** Stubs forbid storage I/O during startup. */
  @Singleton
  public static class Stores {
    @Produces
    @Singleton
    DocumentStore documents() {
      return stub(DocumentStore.class);
    }

    @Produces
    @Singleton
    IndexStore indexes() {
      return stub(IndexStore.class);
    }

    @Produces
    @Singleton
    dev.nexcraft.r2d1.PersistenceCollectionFactory.CollectionInitializer initializer() {
      return type -> java.util.concurrent.CompletableFuture.completedFuture(null);
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
