package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class DocumentCursorTest {

  @Test
  void retainsAnOpaqueCursorValue() {
    assertThat(new DocumentCursor("opaque-token").value()).isEqualTo("opaque-token");
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void requiresANonBlankValue() {
    assertThatNullPointerException()
        .isThrownBy(() -> new DocumentCursor(null))
        .withMessage("value");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new DocumentCursor(" "))
        .withMessage("value must not be blank");
  }
}
