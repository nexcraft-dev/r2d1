package dev.nexcraft.r2d1.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.quarkus.internal.OwnedResources;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** D1 wiring reuses application HTTP clients without making cloud calls. */
class D1IntegrationTest {
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
          .overrideConfigKey("quarkus.r2d1.index.type", "d1")
          .overrideConfigKey("quarkus.r2d1.d1.account-id", "test-account")
          .overrideConfigKey("quarkus.r2d1.d1.database-id", "test-db")
          .overrideConfigKey("quarkus.r2d1.d1.api-token", "test-token");

  @Test
  void borrowsHttpClientAndDoesNotCloseIt() {
    assertThat(Arc.container().instance(IndexStore.class).get()).isInstanceOf(D1IndexStore.class);
    final var client = Arc.container().instance(HttpClient.class).get();
    Arc.container().instance(OwnedResources.class).get().close();
    assertThat(client.isTerminated()).isFalse();
    client.close();
  }

  /** Application-owned HTTP client, never asked to send a request. */
  @Singleton
  public static class Client {
    @Produces
    @Singleton
    HttpClient client() {
      return HttpClient.newHttpClient();
    }
  }
}
