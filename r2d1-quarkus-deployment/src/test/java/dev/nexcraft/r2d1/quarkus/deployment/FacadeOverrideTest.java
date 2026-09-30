package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A custom facade suppresses all owned storage despite incomplete backend settings. */
class FacadeOverrideTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .withApplicationRoot(
              root -> root.addClasses(ApplicationFacade.class, UnusedFactory.class))
          .overrideConfigKey("quarkus.r2d1.enabled", "true")
          .overrideConfigKey("quarkus.r2d1.document.type", "r2")
          .overrideConfigKey("quarkus.r2d1.index.type", "d1");

  @Test
  void skipsAllBackendValidation() {
    assertThat(Arc.container().instance(R2D1.class).isAvailable()).isTrue();
  }

  /** Application facade with no adapter dependencies. */
  @Singleton
  public static class ApplicationFacade {
    @Produces
    @Singleton
    R2D1 facade() {
      return R2D1.builder().collectionFactory(new UnusedFactory()).build();
    }
  }

  /** Test-only factory that is never asked to create a collection. */
  public static class UnusedFactory implements R2D1.CollectionFactory {
    @Override
    public <T> R2D1Collection<T> create(final Class<T> type) {
      throw new UnsupportedOperationException();
    }
  }
}
