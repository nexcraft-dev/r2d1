package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class DocumentNotFoundExceptionTest {

  @Test
  void retainsTheMissingDocumentKey() {
    DocumentKey key = new DocumentKey("users", "user-123");

    DocumentNotFoundException exception = new DocumentNotFoundException(key);

    assertThat(exception)
        .isInstanceOf(StorageException.class)
        .hasMessage("Document not found: users/user-123")
        .hasNoCause();
    assertThat(exception.documentKey()).isSameAs(key);
  }

  @Test
  void rejectsANullDocumentKey() {
    assertThatNullPointerException()
        .isThrownBy(() -> new DocumentNotFoundException(null))
        .withMessage("documentKey");
  }
}
