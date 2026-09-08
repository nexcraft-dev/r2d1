package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageTest {

  @Test
  void defensivelyCopiesItems() {
    List<String> source = new ArrayList<>(List.of("first"));

    Page<String> page = new Page<>(source, "next");
    source.add("second");

    assertThat(page.items()).containsExactly("first");
    assertThatThrownBy(() -> page.items().add("third"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void reportsWhetherANextPageExists() {
    assertThat(new Page<>(List.of("first"), "next").hasNextPage()).isTrue();
    assertThat(new Page<>(List.of("last"), null).hasNextPage()).isFalse();
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullItems() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Page<String>(null, null))
        .withMessage("items");
  }

  @Test
  void rejectsNullItemsInsideThePage() {
    List<String> items = new ArrayList<>();
    items.add(null);

    assertThatNullPointerException().isThrownBy(() -> new Page<>(items, null));
  }

  @Test
  void rejectsABlankNextCursor() {
    assertThatThrownBy(() -> new Page<>(List.of("first"), " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("nextCursor must not be blank");
  }
}
