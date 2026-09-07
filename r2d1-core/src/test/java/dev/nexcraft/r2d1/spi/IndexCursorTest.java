package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class IndexCursorTest {

  @Test
  void representsSortedAndUnsortedPositions() {
    DocumentKey key = new DocumentKey("users", "user-123");
    IndexValue sortValue = new IndexValue.LongValue(42);

    assertThat(new IndexCursor(key, Optional.empty()).sortValue()).isEmpty();
    assertThat(new IndexCursor(key, Optional.of(sortValue)).sortValue()).contains(sortValue);
  }

  @Test
  void rejectsNullComponents() {
    DocumentKey key = new DocumentKey("users", "user-123");

    assertThatNullPointerException()
        .isThrownBy(() -> new IndexCursor(null, Optional.empty()))
        .withMessage("lastDocumentKey");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexCursor(key, null))
        .withMessage("sortValue");
  }
}
