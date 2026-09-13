package dev.nexcraft.r2d1.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.r2.R2DocumentStore;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3AsyncClient;

class MicronautOverrideAndAdapterTest {

  @Test
  void applicationCollectionFactoryWinsWithoutStorageConfiguration() {
    AtomicInteger calls = new AtomicInteger();
    R2D1.CollectionFactory collectionFactory =
        new R2D1.CollectionFactory() {
          @Override
          public <T> R2D1Collection<T> create(Class<T> documentType) {
            calls.incrementAndGet();
            throw new UnsupportedOperationException(documentType.getName());
          }
        };

    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(Map.of("r2d1.enabled", true))
            .singletons(collectionFactory)
            .start()) {
      assertThatThrownBy(
              () -> context.getBean(R2D1.class).collection(TestDocumentSupport.User.class))
          .isInstanceOf(UnsupportedOperationException.class);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void applicationDocumentStoreSuppressesR2Configuration() {
    TestDocumentSupport.MemoryDocumentStore documentStore =
        new TestDocumentSupport.MemoryDocumentStore();
    Map<String, Object> properties =
        Map.of(
            "r2d1.enabled", true,
            "r2d1.index.type", "jdbc",
            "r2d1.r2.bucket-name", "configured-but-overridden");

    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(properties)
            .singletons(documentStore, new TestDocumentSupport.UserCodec())
            .beanDefinitions(bean(DataSource.class, dataSource("override")))
            .start()) {
      assertThat(context.getBean(DocumentStore.class)).isSameAs(documentStore);
      assertThat(context.containsBean(R2DocumentStore.class)).isFalse();
    }
  }

  @Test
  void customIndexStoreRequiresACorrespondingInitializer() {
    IndexStore indexStore = unsupportedProxy(IndexStore.class);

    assertThatThrownBy(
            () ->
                ApplicationContext.builder()
                    .properties(Map.of("r2d1.enabled", true))
                    .singletons(
                        new TestDocumentSupport.MemoryDocumentStore(),
                        new TestDocumentSupport.UserCodec(),
                        indexStore)
                    .start())
        .hasMessageContaining("No collection initializer exists")
        .hasMessageContaining("PersistenceCollectionFactory.CollectionInitializer");
  }

  @Test
  void applicationIndexStoreAndInitializerWinWithoutBackendSelection() {
    IndexStore indexStore = unsupportedProxy(IndexStore.class);
    dev.nexcraft.r2d1.PersistenceCollectionFactory.CollectionInitializer initializer =
        documentType -> CompletableFuture.completedFuture(null);

    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(Map.of("r2d1.enabled", true))
            .singletons(
                new TestDocumentSupport.MemoryDocumentStore(), new TestDocumentSupport.UserCodec())
            .beanDefinitions(
                bean(IndexStore.class, indexStore),
                bean(
                    dev.nexcraft.r2d1.PersistenceCollectionFactory.CollectionInitializer.class,
                    initializer))
            .start()) {
      assertThat(context.getBean(IndexStore.class)).isSameAs(indexStore);
      assertThat(context.getBean(R2D1.class)).isNotNull();
    }
  }

  @Test
  void borrowedS3ClientIsNotClosedWithTheContext() {
    AtomicInteger closeCalls = new AtomicInteger();
    S3AsyncClient client = s3Client(closeCalls);
    Map<String, Object> properties =
        Map.of(
            "r2d1.enabled", true,
            "r2d1.index.type", "jdbc",
            "r2d1.r2.bucket-name", "documents");

    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(properties)
            .singletons(new TestDocumentSupport.UserCodec())
            .beanDefinitions(
                bean(DataSource.class, dataSource("borrowedS3")), bean(S3AsyncClient.class, client))
            .start()) {
      assertThat(context.getBean(DocumentStore.class)).isInstanceOf(R2DocumentStore.class);
    }

    assertThat(closeCalls).hasValue(0);
  }

  @Test
  void ambiguousS3ClientsFailBeforeNetworkUse() {
    Map<String, Object> properties =
        Map.of(
            "r2d1.enabled", true,
            "r2d1.index.type", "jdbc",
            "r2d1.r2.bucket-name", "documents");

    assertThatThrownBy(
            () ->
                ApplicationContext.builder()
                    .properties(properties)
                    .singletons(new TestDocumentSupport.UserCodec())
                    .beanDefinitions(
                        bean(DataSource.class, dataSource("ambiguousS3")),
                        namedBean(S3AsyncClient.class, "first", s3Client(new AtomicInteger())),
                        namedBean(S3AsyncClient.class, "second", s3Client(new AtomicInteger())))
                    .start())
        .hasMessageContaining("Multiple S3AsyncClient beans")
        .hasMessageContaining("@Primary");
  }

  @Test
  void completeR2ConfigurationCreatesAndClosesAnOwnedStoreWithoutNetworkUse() {
    Map<String, Object> properties =
        Map.of(
            "r2d1.enabled", true,
            "r2d1.index.type", "jdbc",
            "r2d1.r2.endpoint", "https://example.r2.cloudflarestorage.com",
            "r2d1.r2.access-key-id", "test-access",
            "r2d1.r2.secret-access-key", "test-secret",
            "r2d1.r2.bucket-name", "documents");

    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(properties)
            .singletons(new TestDocumentSupport.UserCodec())
            .beanDefinitions(bean(DataSource.class, dataSource("ownedS3")))
            .start()) {
      assertThat(context.getBean(DocumentStore.class)).isInstanceOf(R2DocumentStore.class);
    }
  }

  @Test
  void partialR2CredentialsExplainTheMissingProperty() {
    Map<String, Object> properties =
        Map.of(
            "r2d1.enabled", true,
            "r2d1.index.type", "jdbc",
            "r2d1.r2.endpoint", "https://example.r2.cloudflarestorage.com",
            "r2d1.r2.bucket-name", "documents");

    assertThatThrownBy(
            () ->
                ApplicationContext.builder()
                    .properties(properties)
                    .singletons(new TestDocumentSupport.UserCodec())
                    .beanDefinitions(bean(DataSource.class, dataSource("partialS3")))
                    .start())
        .hasMessageContaining("r2d1.r2.access-key-id")
        .hasMessageContaining("non-blank");
  }

  @Test
  void missingR2BucketExplainsTheRequiredProperty() {
    Map<String, Object> properties =
        Map.of(
            "r2d1.enabled", true,
            "r2d1.index.type", "jdbc",
            "r2d1.r2.endpoint", "https://example.r2.cloudflarestorage.com",
            "r2d1.r2.access-key-id", "test-access",
            "r2d1.r2.secret-access-key", "test-secret");

    assertThatThrownBy(
            () ->
                ApplicationContext.builder()
                    .properties(properties)
                    .singletons(new TestDocumentSupport.UserCodec())
                    .beanDefinitions(bean(DataSource.class, dataSource("missingBucket")))
                    .start())
        .hasMessageContaining("r2d1.r2.bucket-name")
        .hasMessageContaining("non-blank");
  }

  @Test
  void borrowedJavaHttpClientIsNotClosedWithTheContext() {
    HttpClient client = HttpClient.newHttpClient();
    Map<String, Object> properties = d1Properties();
    try {
      try (ApplicationContext context =
          ApplicationContext.builder()
              .properties(properties)
              .singletons(
                  new TestDocumentSupport.MemoryDocumentStore(),
                  new TestDocumentSupport.UserCodec())
              .beanDefinitions(bean(HttpClient.class, client))
              .start()) {
        assertThat(context.getBean(IndexStore.class)).isInstanceOf(D1IndexStore.class);
      }
      assertThat(client.isTerminated()).isFalse();
    } finally {
      client.close();
    }
  }

  @Test
  void ambiguousJavaHttpClientsFailBeforeNetworkUse() {
    HttpClient first = HttpClient.newHttpClient();
    HttpClient second = HttpClient.newHttpClient();
    try {
      assertThatThrownBy(
              () ->
                  ApplicationContext.builder()
                      .properties(d1Properties())
                      .singletons(
                          new TestDocumentSupport.MemoryDocumentStore(),
                          new TestDocumentSupport.UserCodec())
                      .beanDefinitions(
                          namedBean(HttpClient.class, "first", first),
                          namedBean(HttpClient.class, "second", second))
                      .start())
          .hasMessageContaining("Multiple java.net.http.HttpClient beans")
          .hasMessageContaining("@Primary");
      assertThat(first.isTerminated()).isFalse();
      assertThat(second.isTerminated()).isFalse();
    } finally {
      first.close();
      second.close();
    }
  }

  @Test
  void completeD1ConfigurationCreatesAndClosesAnOwnedStoreWithoutNetworkUse() {
    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(d1Properties())
            .singletons(
                new TestDocumentSupport.MemoryDocumentStore(), new TestDocumentSupport.UserCodec())
            .start()) {
      assertThat(context.getBean(IndexStore.class)).isInstanceOf(D1IndexStore.class);
    }
  }

  @Test
  void partialD1CredentialsExplainTheMissingProperty() {
    Map<String, Object> properties = new LinkedHashMap<>(d1Properties());
    properties.remove("r2d1.d1.api-token");

    assertThatThrownBy(
            () ->
                ApplicationContext.builder()
                    .properties(properties)
                    .singletons(
                        new TestDocumentSupport.MemoryDocumentStore(),
                        new TestDocumentSupport.UserCodec())
                    .start())
        .hasMessageContaining("r2d1.d1.api-token")
        .hasMessageContaining("non-blank");
  }

  private static Map<String, Object> d1Properties() {
    return Map.of(
        "r2d1.enabled", true,
        "r2d1.index.type", "d1",
        "r2d1.d1.account-id", "account",
        "r2d1.d1.database-id", "database",
        "r2d1.d1.api-token", "token");
  }

  private static JdbcDataSource dataSource(String name) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");
    return dataSource;
  }

  private static S3AsyncClient s3Client(AtomicInteger closeCalls) {
    return (S3AsyncClient)
        Proxy.newProxyInstance(
            S3AsyncClient.class.getClassLoader(),
            new Class<?>[] {S3AsyncClient.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("close")) {
                closeCalls.incrementAndGet();
                return null;
              }
              if (method.getName().equals("toString")) {
                return "TestS3AsyncClient";
              }
              if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
              }
              if (method.getName().equals("equals")) {
                return proxy == arguments[0];
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static <T> T unsupportedProxy(Class<T> type) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, arguments) -> {
              if (method.getName().equals("toString")) {
                return "Unsupported" + type.getSimpleName();
              }
              if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
              }
              if (method.getName().equals("equals")) {
                return proxy == arguments[0];
              }
              throw new UnsupportedOperationException(method.getName());
            }));
  }

  private static <T> RuntimeBeanDefinition<T> bean(Class<T> type, T instance) {
    return RuntimeBeanDefinition.builder(type, () -> instance).build();
  }

  private static <T> RuntimeBeanDefinition<T> namedBean(Class<T> type, String name, T instance) {
    return RuntimeBeanDefinition.builder(type, () -> instance).named(name).build();
  }
}
