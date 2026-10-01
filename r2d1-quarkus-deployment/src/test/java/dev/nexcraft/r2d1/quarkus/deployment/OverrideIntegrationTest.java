package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Proves an application factory bypasses storage configuration and codec requirements. */
class OverrideIntegrationTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .withApplicationRoot(root -> root.addClass(ApplicationFactory.class))
          .overrideConfigKey("quarkus.r2d1.enabled", "true");

  @Test
  void usesApplicationFactoryWithoutBackendSettings() {
    assertThat(Arc.container().instance(R2D1.class).isAvailable()).isTrue();
    assertThat(Arc.container().instance(R2D1.CollectionFactory.class).get())
        .isInstanceOf(ApplicationFactory.class);
  }

  /** Application-owned factory with no storage dependencies. */
  @Singleton
  public static class ApplicationFactory implements R2D1.CollectionFactory {
    @Override
    public <T> R2D1Collection<T> create(final Class<T> type) {
      throw new UnsupportedOperationException("No collection requested by wiring test");
    }
  }
}
