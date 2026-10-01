package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.quarkus.internal.BeanSelection;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Selected optional adapter has an actionable diagnostic when absent. */
class MissingJdbcTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .overrideConfigKey(
              "quarkus.class-loading.removed-artifacts",
              "dev.nexcraft:r2d1-filesystem,dev.nexcraft:r2d1-jdbc")
          .withApplicationRoot(
              root -> root.addClasses(MissingAdapterTest.class, MissingAdapterTest.Factory.class))
          .overrideConfigKey("quarkus.r2d1.enabled", "true")
          .overrideConfigKey("quarkus.r2d1.index.type", "jdbc");

  @Test
  void identifiesMissingArtifact() {
    final var beans = Arc.container().instance(BeanSelection.class).get();
    assertThatThrownBy(() -> beans.required(IndexStore.class, Optional.empty()))
        .hasStackTraceContaining("r2d1-jdbc");
  }
}
