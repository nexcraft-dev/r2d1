package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.quarkus.internal.OwnedResources;
import dev.nexcraft.r2d1.r2.R2DocumentStore;
import dev.nexcraft.r2d1.spi.DocumentStore;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/** R2 uses a borrowed client without requiring owned-client credentials or network I/O. */
class R2IntegrationTest {
  @RegisterExtension
  static final QuarkusExtensionTest APP =
      new QuarkusExtensionTest()
          .withApplicationRoot(
              root ->
                  root.addClasses(
                      OverrideIntegrationTest.class,
                      OverrideIntegrationTest.ApplicationFactory.class,
                      Client.class))
          .overrideConfigKey("quarkus.r2d1.enabled", "true")
          .overrideConfigKey("quarkus.r2d1.document.type", "r2")
          .overrideConfigKey("quarkus.r2d1.r2.bucket-name", "test-bucket");

  @Test
  void borrowsS3ClientAndDoesNotCloseIt() {
    assertThat(Arc.container().instance(DocumentStore.class).get())
        .isInstanceOf(R2DocumentStore.class);
    final var client = Arc.container().instance(Client.class).get();
    Arc.container().instance(OwnedResources.class).get().close();
    assertThat(client.closed.get()).isFalse();
  }

  /** Proxy throws on unexpected network calls and tracks close requests. */
  @Singleton
  public static class Client {
    final AtomicBoolean closed = new AtomicBoolean();

    @Produces
    @Singleton
    S3AsyncClient client() {
      return (S3AsyncClient)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {S3AsyncClient.class},
              (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                  closed.set(true);
                  return null;
                }
                if (method.getName().equals("serviceName")) return "s3";
                if (method.getName().equals("toString")) return "Test S3 client";
                throw new AssertionError("Unexpected client invocation: " + method.getName());
              });
    }
  }
}
