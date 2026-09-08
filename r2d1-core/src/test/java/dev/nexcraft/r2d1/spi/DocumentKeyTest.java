package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocumentKeyTest {

  @Test
  void scopesIdentityToTheCollection() {
    DocumentKey usersKey = new DocumentKey("users", "shared-id");

    assertThat(usersKey).isEqualTo(new DocumentKey("users", "shared-id"));
    assertThat(usersKey).isNotEqualTo(new DocumentKey("orders", "shared-id"));
  }

  @Test
  void preservesComponentsWithoutNormalization() {
    DocumentKey key = new DocumentKey(" users ", " id ");

    assertThat(key.collection()).isEqualTo(" users ");
    assertThat(key.id()).isEqualTo(" id ");
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullComponents() {
    assertThatNullPointerException()
        .isThrownBy(() -> new DocumentKey(null, "id"))
        .withMessage("collection");
    assertThatNullPointerException()
        .isThrownBy(() -> new DocumentKey("users", null))
        .withMessage("id");
  }

  @Test
  void rejectsBlankComponents() {
    assertThatThrownBy(() -> new DocumentKey(" ", "id"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("collection must not be blank");
    assertThatThrownBy(() -> new DocumentKey("users", "\t"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("id must not be blank");
  }
}
