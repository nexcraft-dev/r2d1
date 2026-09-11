package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDatabase;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Method;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class JdbcJSpecifyContractTest {

  @Test
  void nullMarksTheJdbcAdapterPackage() {
    assertThat(JdbcIndexStore.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
    assertThat(JdbcDatabase.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
    assertThat(JdbcMetadata.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
  }

  @Test
  void marksVoidCompletionValuesAsNullable() throws NoSuchMethodException {
    assertNullableCompletionValue(JdbcIndexStore.class.getMethod("initialize", Class.class));
    assertNullableCompletionValue(JdbcIndexStore.class.getMethod("clear", String.class));
    assertNullableCompletionValue(
        JdbcIndexStore.class.getMethod("upsert", dev.nexcraft.r2d1.spi.IndexEntry.class));
    assertNullableCompletionValue(
        JdbcIndexStore.class.getMethod("delete", dev.nexcraft.r2d1.spi.DocumentKey.class));
  }

  private static void assertNullableCompletionValue(Method method) {
    AnnotatedParameterizedType returnType =
        (AnnotatedParameterizedType) method.getAnnotatedReturnType();

    assertThat(returnType.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class))
        .isTrue();
  }
}
