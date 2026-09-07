package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class JSpecifyContractTest {

  @Test
  void nullMarksEveryCorePublicPackage() {
    assertThat(R2D1.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
    assertThat(Document.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
    assertThat(DocumentStore.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
  }

  @Test
  void marksThePublicNullableCursor() {
    RecordComponent nextCursor = Page.class.getRecordComponents()[1];

    assertThat(nextCursor.getAnnotatedType().isAnnotationPresent(Nullable.class)).isTrue();
  }

  @Test
  void marksTheEqualsParameterAsNullable() throws NoSuchMethodException {
    Method equals = StoredDocument.class.getMethod("equals", Object.class);

    assertThat(equals.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class)).isTrue();
  }
}
