package dev.nexcraft.r2d1.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.quarkus.internal.OwnedResources;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Verifies cleanup order, idempotence and continued cleanup after failure. */
class OwnedResourcesTest {
  @Test
  void closesOnlyRegisteredResourcesInReverseOrderOnce() {
    final var calls = new ArrayList<Integer>();
    final var resources = new OwnedResources();
    resources.own(() -> calls.add(1));
    resources.own(() -> calls.add(2));
    resources.close();
    resources.close();
    assertThat(calls).containsExactly(2, 1);
  }

  @Test
  void stillClosesRemainingResourcesAfterFailure() {
    final var calls = new ArrayList<Integer>();
    final var resources = new OwnedResources();
    resources.own(() -> calls.add(1));
    resources.own(
        () -> {
          throw new IllegalStateException("Test failure");
        });
    assertThatThrownBy(resources::close).hasMessage("Failed to close R2D1-owned resources");
    assertThat(calls).containsExactly(1);
    resources.close();
  }
}
