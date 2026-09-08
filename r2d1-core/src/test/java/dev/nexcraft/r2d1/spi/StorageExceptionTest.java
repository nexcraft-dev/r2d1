package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StorageExceptionTest {

  @Test
  void retainsItsMessage() {
    StorageException exception = new StorageException("storage failed");

    assertThat(exception).hasMessage("storage failed").hasNoCause();
  }

  @Test
  void retainsItsMessageAndCause() {
    RuntimeException cause = new RuntimeException("adapter failure");
    StorageException exception = new StorageException("storage failed", cause);

    assertThat(exception).hasMessage("storage failed").hasCause(cause);
  }

  @Test
  void classifiesStorageFailuresWithoutChangingTheBaseType() {
    RuntimeException cause = new RuntimeException("adapter failure");

    assertThat(new StorageException.Access("access", cause))
        .isInstanceOf(StorageException.class)
        .hasCause(cause);
    assertThat(new StorageException.Unavailable("unavailable", cause))
        .isInstanceOf(StorageException.class)
        .hasCause(cause);
    assertThat(new StorageException.Operation("operation", cause))
        .isInstanceOf(StorageException.class)
        .hasCause(cause);
  }
}
