package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class PublicApiSynchronousContractTest {

  @Test
  void collectionOperationsRemainSynchronous() throws NoSuchMethodException {
    assertReturnType(R2D1Collection.class, "put", void.class, Object.class);
    assertReturnType(R2D1Collection.class, "get", Optional.class, String.class);
    assertReturnType(R2D1Collection.class, "delete", void.class, String.class);
    assertReturnType(R2D1Collection.class, "query", Query.class);
  }

  @Test
  void queryExecutionRemainsSynchronous() throws NoSuchMethodException {
    assertReturnType(Query.class, "fetch", Page.class);
    assertReturnType(Query.Executor.class, "execute", Page.class, Query.Request.class);
  }

  private static void assertReturnType(
      Class<?> owner, String methodName, Class<?> returnType, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    Method method = owner.getMethod(methodName, parameterTypes);

    assertThat(method.getReturnType()).isEqualTo(returnType).isNotEqualTo(CompletionStage.class);
  }
}
