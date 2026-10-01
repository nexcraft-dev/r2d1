package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.quarkus.internal.BeanSelection;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Virtual threads follow the JDBC adapter's explicit Java 25 runtime contract. */
class JdbcVirtualThreadTest {
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
          .overrideConfigKey("quarkus.r2d1.jdbc.execution-mode", "virtual-thread");

  @Test
  void honorsRuntimeRequirement() {
    final var beans = Arc.container().instance(BeanSelection.class).get();
    if (Runtime.version().feature() < 25) {
      assertThatThrownBy(() -> beans.required(IndexStore.class, Optional.empty()))
          .hasStackTraceContaining("Java 25");
    } else {
      final var store = beans.required(IndexStore.class, Optional.empty());
      Arc.container()
          .instance(dev.nexcraft.r2d1.quarkus.internal.OwnedResources.class)
          .get()
          .initializer(store)
          .initialize(TestSupport.User.class)
          .toCompletableFuture()
          .join();
    }
  }
}
