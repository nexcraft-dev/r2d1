package dev.nexcraft.r2d1.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnnotationContractTest {

  @Test
  void documentAnnotationHasRuntimeTypeMetadata() {
    assertThat(Document.class.getAnnotation(Retention.class).value())
        .isEqualTo(RetentionPolicy.RUNTIME);
    assertThat(Document.class.getAnnotation(Target.class).value())
        .containsExactly(ElementType.TYPE);
    assertThat(Document.class.isAnnotationPresent(Documented.class)).isTrue();
    assertThat(AnnotatedDocument.class.getAnnotation(Document.class).value()).isEqualTo("users");
  }

  @Test
  void idAnnotationSupportsFieldsAndRecordComponents() {
    assertThat(Id.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
    assertThat(Set.of(Id.class.getAnnotation(Target.class).value()))
        .containsExactlyInAnyOrder(ElementType.FIELD, ElementType.RECORD_COMPONENT);
    assertThat(Id.class.isAnnotationPresent(Documented.class)).isTrue();
    assertThat(AnnotatedDocument.class.getDeclaredFields()[0].isAnnotationPresent(Id.class))
        .isTrue();
  }

  @Test
  void indexAnnotationHasMinimalSortableMetadata() throws NoSuchFieldException {
    assertThat(Index.class.getAnnotation(Retention.class).value())
        .isEqualTo(RetentionPolicy.RUNTIME);
    assertThat(Set.of(Index.class.getAnnotation(Target.class).value()))
        .containsExactlyInAnyOrder(ElementType.FIELD, ElementType.RECORD_COMPONENT);
    assertThat(Index.class.isAnnotationPresent(Documented.class)).isTrue();
    assertThat(
            AnnotatedDocument.class
                .getDeclaredField("country")
                .getAnnotation(Index.class)
                .sortable())
        .isFalse();
    assertThat(
            AnnotatedDocument.class
                .getDeclaredField("createdAt")
                .getAnnotation(Index.class)
                .sortable())
        .isTrue();
  }

  @Document("users")
  private static final class AnnotatedDocument {

    @Id private String id;

    @Index private String country;

    @Index(sortable = true)
    private long createdAt;
  }
}
