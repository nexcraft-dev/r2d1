package dev.nexcraft.r2d1.d1.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.d1.internal.metadata.D1Metadata;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class D1JSpecifyContractTest {

  @Test
  void nullMarksTheD1AdapterPackage() {
    assertThat(D1IndexStore.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
    assertThat(D1Protocol.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
    assertThat(D1Metadata.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
  }

  @Test
  void marksNullableProtocolContainersElementsAndRowValues() {
    RecordComponent result = D1Protocol.ApiResponse.class.getRecordComponents()[1];
    RecordComponent errors = D1Protocol.ApiResponse.class.getRecordComponents()[2];
    RecordComponent rows = D1Protocol.QueryResult.class.getRecordComponents()[1];

    assertNullableContainerAndElement(result.getAnnotatedType());
    assertNullableContainerAndElement(errors.getAnnotatedType());

    AnnotatedParameterizedType rowList = (AnnotatedParameterizedType) rows.getAnnotatedType();
    assertThat(rowList.isAnnotationPresent(Nullable.class)).isTrue();
    AnnotatedType rowElement = rowList.getAnnotatedActualTypeArguments()[0];
    assertThat(rowElement.isAnnotationPresent(Nullable.class)).isTrue();
    AnnotatedParameterizedType rowMap = (AnnotatedParameterizedType) rowElement;
    assertThat(rowMap.getAnnotatedActualTypeArguments()[1].isAnnotationPresent(Nullable.class))
        .isTrue();
  }

  @Test
  void marksVoidCompletionValuesAsNullable() throws NoSuchMethodException {
    assertNullableCompletionValue(D1IndexStore.class.getMethod("initialize", Class.class));
    assertNullableCompletionValue(D1IndexStore.class.getMethod("clear", String.class));
  }

  private static void assertNullableCompletionValue(Method method) {
    AnnotatedParameterizedType returnType =
        (AnnotatedParameterizedType) method.getAnnotatedReturnType();

    assertThat(returnType.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class))
        .isTrue();
  }

  private static void assertNullableContainerAndElement(AnnotatedType type) {
    AnnotatedParameterizedType container = (AnnotatedParameterizedType) type;
    assertThat(container.isAnnotationPresent(Nullable.class)).isTrue();
    assertThat(container.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class))
        .isTrue();
  }
}
