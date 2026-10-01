package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.R2D1;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Proves disabled integration creates no facade or owned resources. */
class DisabledIntegrationTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP = new QuarkusExtensionTest().withEmptyApplication();

  @Test
  void doesNotCreateFacadeByDefault() {
    assertThat(Arc.container().instance(R2D1.class).isAvailable()).isFalse();
  }
}
