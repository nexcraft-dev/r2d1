package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A custom factory works without either optional adapter on the classpath. */
class MissingAdapterTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .overrideConfigKey(
              "quarkus.class-loading.removed-artifacts",
              "dev.nexcraft:r2d1-filesystem,dev.nexcraft:r2d1-jdbc")
          .withApplicationRoot(root -> root.addClass(Factory.class))
          .overrideConfigKey("quarkus.r2d1.enabled", "true")
          .overrideConfigKey("quarkus.r2d1.document.type", "filesystem")
          .overrideConfigKey("quarkus.r2d1.index.type", "jdbc");

  @Test
  void bypassesMissingAdapterWhenFactoryIsProvided() {
    assertThat(Arc.container().instance(R2D1.class).isAvailable()).isTrue();
  }

  /** Application factory without JDBC/filesystem dependencies. */
  @Singleton
  public static class Factory implements R2D1.CollectionFactory {
    @Override
    public <T> R2D1Collection<T> create(final Class<T> type) {
      throw new UnsupportedOperationException();
    }
  }
}
