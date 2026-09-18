package dev.nexcraft.r2d1.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentCursor;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.StoredDocument;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.core.annotation.ReflectiveAccess;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class NativeH2IntegrationTest {

  @Test
  void createsR2d1AndRunsPublicOperationsInANativeImage() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:nativeMicronaut;DB_CLOSE_DELAY=-1");
    dataSource.setUser("sa");
    dataSource.setPassword("");

    try (ApplicationContext context =
        ApplicationContext.builder()
            .properties(Map.of("r2d1.enabled", true, "r2d1.index.type", "jdbc"))
            .singletons(new MemoryDocumentStore())
            .beanDefinitions(
                RuntimeBeanDefinition.builder(DataSource.class, () -> dataSource).build())
            .start()) {
      R2D1Collection<NativeUser> users =
          context.getBean(R2D1.class).collection(NativeUser.class, new NativeUserCodec());
      NativeUser user = new NativeUser("native-1", "NZ", "Aroha");

      users.put(user);
      assertThat(users.get(user.id())).contains(user);
      assertThat(users.query().where("country").eq("NZ").limit(10).fetch().items())
          .containsExactly(user);
      users.delete(user.id());
      assertThat(users.get(user.id())).isEmpty();
    }
  }

  @Document("native_users")
  @ReflectiveAccess
  record NativeUser(@Id String id, @Index String country, String name) {}

  static final class NativeUserCodec implements DocumentCodec<NativeUser> {

    @Override
    public String id() {
      return "native-test-codec";
    }

    @Override
    public String format() {
      return "native-test";
    }

    @Override
    public byte[] encode(NativeUser user) {
      String value = user.id() + "\n" + user.country() + "\n" + user.name();
      return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public NativeUser decode(byte[] data) {
      String[] values = new String(data, StandardCharsets.UTF_8).split("\n", -1);
      return new NativeUser(values[0], values[1], values[2]);
    }
  }

  static final class MemoryDocumentStore implements DocumentStore {

    private final Map<DocumentKey, StoredDocument> documents = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<DocumentPage> list(
        String collection, @Nullable DocumentCursor cursor, int limit) {
      throw new UnsupportedOperationException("list is not used by this native smoke test");
    }

    @Override
    public CompletionStage<@Nullable Void> put(DocumentKey key, StoredDocument document) {
      documents.put(key, document);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<StoredDocument> get(DocumentKey key) {
      StoredDocument document = documents.get(key);
      return document == null
          ? CompletableFuture.failedFuture(new DocumentNotFoundException(key))
          : CompletableFuture.completedFuture(document);
    }

    @Override
    public CompletionStage<@Nullable Void> delete(DocumentKey key) {
      documents.remove(key);
      return CompletableFuture.completedFuture(null);
    }
  }
}
