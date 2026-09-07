package dev.nexcraft.r2d1.r2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.nexcraft.r2d1.spi.DocumentKey;
import org.junit.jupiter.api.Test;

class R2ObjectKeyTest {

  @Test
  void keepsUnreservedCharactersReadable() {
    DocumentKey key = new DocumentKey("Users-._~09", "user-123");

    assertThat(R2ObjectKey.from(key)).isEqualTo("Users-._~09/user-123");
  }

  @Test
  void percentEncodesEachUtf8ComponentIndependently() {
    DocumentKey key = new DocumentKey("a/b", "café %");

    assertThat(R2ObjectKey.from(key)).isEqualTo("a%2Fb/caf%C3%A9%20%25");
  }

  @Test
  void preventsAmbiguousComponentBoundaries() {
    String collectionContainsSlash = R2ObjectKey.from(new DocumentKey("a/b", "c"));
    String idContainsSlash = R2ObjectKey.from(new DocumentKey("a", "b/c"));

    assertThat(collectionContainsSlash).isEqualTo("a%2Fb/c");
    assertThat(idContainsSlash).isEqualTo("a/b%2Fc");
    assertThat(collectionContainsSlash).isNotEqualTo(idContainsSlash);
  }

  @Test
  void rejectsNullDocumentKey() {
    assertThatNullPointerException()
        .isThrownBy(() -> R2ObjectKey.from(null))
        .withMessage("documentKey");
  }
}
