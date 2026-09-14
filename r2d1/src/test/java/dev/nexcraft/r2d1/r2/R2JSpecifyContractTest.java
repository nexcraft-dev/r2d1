package dev.nexcraft.r2d1.r2;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

class R2JSpecifyContractTest {

  @Test
  void nullMarksTheR2AdapterPackage() {
    assertThat(R2DocumentStore.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
  }
}
