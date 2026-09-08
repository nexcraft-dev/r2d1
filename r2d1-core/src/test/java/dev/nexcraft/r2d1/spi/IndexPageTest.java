package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IndexPageTest {

  @Test
  void defensivelyCopiesDocumentKeys() {
    DocumentKey key = new DocumentKey("users", "user-123");
    List<DocumentKey> keys = new ArrayList<>(List.of(key));
    IndexCursor cursor = new IndexCursor(key, Optional.empty());

    IndexPage page = new IndexPage(keys, Optional.of(cursor));
    keys.clear();

    assertThat(page.documentKeys()).containsExactly(key);
    assertThat(page.nextCursor()).contains(cursor);
    assertThatThrownBy(() -> page.documentKeys().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void representsACompleteEmptyPage() {
    IndexPage page = new IndexPage(List.of(), Optional.empty());

    assertThat(page.documentKeys()).isEmpty();
    assertThat(page.nextCursor()).isEmpty();
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullComponents() {
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexPage(null, Optional.empty()))
        .withMessage("documentKeys");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexPage(List.of(), null))
        .withMessage("nextCursor");
  }
}
