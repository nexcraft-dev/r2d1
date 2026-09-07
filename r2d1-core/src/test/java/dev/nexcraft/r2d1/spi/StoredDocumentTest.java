package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class StoredDocumentTest {

  @Test
  void defensivelyCopiesInputAndOutput() {
    byte[] input = {1, 2, 3};
    StoredDocument document = new StoredDocument(input);

    input[0] = 9;
    byte[] returned = document.content();
    returned[1] = 9;

    assertThat(document.content()).containsExactly(1, 2, 3);
  }

  @Test
  void usesContentBasedValueEquality() {
    StoredDocument first = new StoredDocument(new byte[] {1, 2, 3});
    StoredDocument same = new StoredDocument(new byte[] {1, 2, 3});
    StoredDocument different = new StoredDocument(new byte[] {1, 2, 4});

    assertThat(first).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(different);
  }

  @Test
  void rejectsNullContent() {
    assertThatNullPointerException()
        .isThrownBy(() -> new StoredDocument(null))
        .withMessage("content");
  }
}
