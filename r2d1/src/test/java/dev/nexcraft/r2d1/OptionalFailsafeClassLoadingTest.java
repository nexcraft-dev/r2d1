package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OptionalFailsafeClassLoadingTest {

  @Test
  void coreApiAndAlternativeProviderWorkWhenFailsafeClassesAreHidden() throws Exception {
    FilteringClassLoader loader = new FilteringClassLoader(getClass().getClassLoader());
    Class<?> configType = Class.forName("dev.nexcraft.r2d1.BackpressureConfig", true, loader);
    Class<?> controllerType = Class.forName("dev.nexcraft.r2d1.AdmissionController", true, loader);
    Class<?> providerType = Class.forName("dev.nexcraft.r2d1.spi.AdmissionProvider", true, loader);
    Class<?> permitSourceType =
        Class.forName("dev.nexcraft.r2d1.spi.AdmissionProvider$PermitSource", true, loader);
    AtomicInteger active = new AtomicInteger();
    InvocationHandler permitHandler =
        (proxy, method, arguments) -> {
          if (method.getName().equals("tryAcquire")) {
            return active.compareAndSet(0, 1);
          }
          if (method.getName().equals("release")) {
            active.decrementAndGet();
            return null;
          }
          return null;
        };
    InvocationHandler providerHandler =
        (proxy, method, arguments) ->
            Proxy.newProxyInstance(loader, new Class<?>[] {permitSourceType}, permitHandler);
    Object provider =
        Proxy.newProxyInstance(loader, new Class<?>[] {providerType}, providerHandler);
    Object config = configType.getConstructor(int.class, int.class).newInstance(1, 0);
    Object controller =
        controllerType.getConstructor(configType, providerType).newInstance(config, provider);
    CompletableFuture<String> operation = new CompletableFuture<>();
    Object result =
        controllerType
            .getMethod("submit", java.util.function.Supplier.class)
            .invoke(
                controller, (java.util.function.Supplier<CompletionStage<String>>) () -> operation);

    assertThat(result).isInstanceOf(CompletionStage.class);
    assertThat(loader.failsafeLoads).hasValue(0);
    operation.complete("complete");
    assertThat(((CompletionStage<?>) result).toCompletableFuture().join()).isEqualTo("complete");
    assertThat(active).hasValue(0);
  }

  private static final class FilteringClassLoader extends ClassLoader {

    private final AtomicInteger failsafeLoads = new AtomicInteger();

    private FilteringClassLoader(ClassLoader parent) {
      super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("dev.failsafe.")) {
        failsafeLoads.incrementAndGet();
        throw new ClassNotFoundException(name);
      }
      if (!name.startsWith("dev.nexcraft.r2d1.")) {
        return super.loadClass(name, resolve);
      }

      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          loaded = defineCoreClass(name);
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }

    private Class<?> defineCoreClass(String name) throws ClassNotFoundException {
      String resourceName = name.replace('.', '/') + ".class";
      try (InputStream stream = getParent().getResourceAsStream(resourceName)) {
        if (stream == null) {
          throw new ClassNotFoundException(name);
        }
        byte[] bytes = stream.readAllBytes();
        int separator = name.lastIndexOf('.');
        if (separator > 0) {
          defineCorePackage(name.substring(0, separator));
        }
        return defineClass(name, bytes, 0, bytes.length);
      } catch (IOException failure) {
        throw new ClassNotFoundException(name, failure);
      }
    }

    private void defineCorePackage(String packageName) {
      synchronized (getClassLoadingLock(packageName)) {
        if (getDefinedPackage(packageName) == null) {
          definePackage(packageName, null, null, null, null, null, null, null);
        }
      }
    }
  }
}
