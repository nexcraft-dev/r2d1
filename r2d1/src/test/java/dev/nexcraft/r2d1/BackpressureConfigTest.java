package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BackpressureConfigTest {

  @Test
  void defaultsToEightActiveAndThirtyTwoPendingOperations() {
    assertThat(BackpressureConfig.DEFAULT).isEqualTo(new BackpressureConfig(8, 32));
  }

  @Test
  void rejectsNonPositiveConcurrency() {
    assertThatThrownBy(() -> new BackpressureConfig(0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxConcurrency must be greater than zero");
    assertThatThrownBy(() -> new BackpressureConfig(-1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativePendingCapacity() {
    assertThatThrownBy(() -> new BackpressureConfig(1, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxPending must not be negative");
  }
}
