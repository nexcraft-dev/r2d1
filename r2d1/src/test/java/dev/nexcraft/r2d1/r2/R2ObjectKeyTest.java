package dev.nexcraft.r2d1.r2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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
  void createsAnExactPrefixAndRoundTripsCanonicalObjectKeys() {
    DocumentKey key = new DocumentKey("a/b", "café / %");

    String objectKey = R2ObjectKey.from(key);

    assertThat(R2ObjectKey.collectionPrefix("a/b")).isEqualTo("a%2Fb/");
    assertThat(R2ObjectKey.toDocumentKey("a/b", objectKey)).isEqualTo(key);
  }

  @Test
  void rejectsForeignNestedAndNonCanonicalObjectKeys() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> R2ObjectKey.toDocumentKey("users", "users_archive/user-1"))
        .withMessage("object key is outside the requested collection");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> R2ObjectKey.toDocumentKey("users", "users/nested/user-1"))
        .withMessage("object key does not contain one encoded document id");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> R2ObjectKey.toDocumentKey("users", "users/%75ser-1"))
        .withMessage("object key does not use canonical segment encoding");
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullDocumentKey() {
    assertThatNullPointerException()
        .isThrownBy(() -> R2ObjectKey.from(null))
        .withMessage("documentKey");
  }
}
