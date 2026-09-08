package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DocumentPageTest {

  @Test
  void defensivelyCopiesDocumentKeys() {
    DocumentKey key = new DocumentKey("users", "user-1");
    List<DocumentKey> keys = new ArrayList<>(List.of(key));

    DocumentPage page = new DocumentPage(keys, Optional.empty());
    keys.clear();

    assertThat(page.documentKeys()).containsExactly(key);
    assertThatThrownBy(() -> page.documentKeys().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void requiresNonNullComponents() {
    assertThatNullPointerException()
        .isThrownBy(() -> new DocumentPage(null, Optional.empty()))
        .withMessage("documentKeys");
    assertThatNullPointerException()
        .isThrownBy(() -> new DocumentPage(List.of(), null))
        .withMessage("nextCursor");
  }
}
