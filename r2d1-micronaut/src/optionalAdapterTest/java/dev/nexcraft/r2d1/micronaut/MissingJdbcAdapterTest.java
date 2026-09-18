package dev.nexcraft.r2d1.micronaut;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.spi.DocumentStore;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MissingJdbcAdapterTest {

  @Test
  void generatedDefinitionsLoadAndExplainAMissingJdbcAdapter() {
    assertThatThrownBy(
            () ->
                ApplicationContext.builder()
                    .properties(Map.of("r2d1.enabled", true, "r2d1.index.type", "jdbc"))
                    .beanDefinitions(bean(DocumentStore.class))
                    .start())
        .hasMessageContaining("index backend 'jdbc'")
        .hasMessageContaining("add the matching R2D1 adapter");
  }

  private static <T> RuntimeBeanDefinition<T> bean(Class<T> type) {
    T instance =
        type.cast(
            Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("toString")) {
                    return "MissingAdapterTest" + type.getSimpleName();
                  }
                  if (method.getName().equals("hashCode")) {
                    return System.identityHashCode(proxy);
                  }
                  if (method.getName().equals("equals")) {
                    return proxy == arguments[0];
                  }
                  throw new UnsupportedOperationException(method.getName());
                }));
    return RuntimeBeanDefinition.builder(type, () -> instance).build();
  }
}
