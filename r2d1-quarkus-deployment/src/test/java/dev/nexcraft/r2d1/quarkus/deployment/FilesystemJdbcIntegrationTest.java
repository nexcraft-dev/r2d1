package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.R2D1;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Exercises actual CDI-backed filesystem/H2 persistence and managed JDBC shutdown. */
class FilesystemJdbcIntegrationTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .withAdditionalDependency(
              jar ->
                  jar.addPackages(true, "dev.nexcraft.r2d1.filesystem", "dev.nexcraft.r2d1.jdbc"))
          .withApplicationRoot(
              root ->
                  root.addClasses(
                      TestSupport.class, TestSupport.Codec.class, TestSupport.User.class))
          .overrideConfigKey("quarkus.r2d1.enabled", "true")
          .overrideConfigKey("quarkus.r2d1.document.type", "filesystem")
          .overrideConfigKey("quarkus.r2d1.index.type", "jdbc")
          .overrideConfigKey("quarkus.r2d1.filesystem.executor", "filesystem")
          .overrideConfigKey("quarkus.r2d1.filesystem.root-directory", directory());

  @Test
  void persistsReadsAndDeletesThroughInjectedFacade() {
    final var facade = Arc.container().instance(R2D1.class).get();
    final var collection = facade.collection(TestSupport.User.class);
    final var user = new TestSupport.User("one", "NZ", "Test User");
    collection.put(user);
    assertThat(collection.get("one")).contains(user);
    assertThat(collection.query().where("country").eq("NZ").limit(10).fetch().items())
        .containsExactly(user);
    collection.delete("one");
    assertThat(collection.get("one")).isEmpty();
  }

  private static String directory() {
    try {
      return Files.createTempDirectory("r2d1-quarkus-test-").toString();
    } catch (final IOException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
