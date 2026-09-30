package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.quarkus.internal.BeanSelection;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Invalid JDBC settings fail before creating an execution resource. */
class JdbcLimitsTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .withAdditionalDependency(jar -> jar.addPackages(true, "dev.nexcraft.r2d1.jdbc"))
          .withApplicationRoot(
              root ->
                  root.addClasses(
                      OverrideIntegrationTest.class,
                      OverrideIntegrationTest.ApplicationFactory.class,
                      TestSupport.class,
                      TestSupport.Codec.class,
                      TestSupport.User.class))
          .overrideConfigKey("quarkus.r2d1.enabled", "true")
          .overrideConfigKey("quarkus.r2d1.index.type", "jdbc")
          .overrideConfigKey("quarkus.r2d1.jdbc.max-concurrency", "0");

  @Test
  void rejectsInvalidSettings() {
    final var beans = Arc.container().instance(BeanSelection.class).get();
    assertThatThrownBy(() -> beans.required(IndexStore.class, Optional.empty()))
        .hasStackTraceContaining("max-concurrency");
  }
}
