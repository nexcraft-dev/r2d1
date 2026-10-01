package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Missing application serialization fails eagerly at runtime startup. */
class ConfigurationFailureTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .withEmptyApplication()
          .overrideConfigKey("quarkus.r2d1.enabled", "true")
          .assertException(failure -> assertThat(failure).hasStackTraceContaining("DocumentCodec"));

  @Test
  void rejectsMissingCodec() {}
}
