package dev.nexcraft.r2d1.quarkus.deployment;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.StoredDocument;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** Isolated application-owned test resources with an explicit simple serialization boundary. */
@Singleton
public class TestSupport {
  /** Test-only direct executor; production applications must supply blocking-I/O threads. */
  @Produces
  @Singleton
  @Named("filesystem")
  Executor executor() {
    return Runnable::run;
  }

  /** A unique embedded datasource for each Quarkus test application. */
  @Produces
  @Singleton
  DataSource datasource() {
    final var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:quarkus-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    return source;
  }

  /** Format-neutral application codec used only by smoke tests. */
  @Singleton
  public static class Codec implements DocumentCodec {
    @Override
    public StoredDocument serialize(final Object document) {
      final User user = (User) document;
      return new StoredDocument(
          (user.id() + "\n" + user.country() + "\n" + user.name())
              .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public <T> T deserialize(final StoredDocument document, final Class<T> type) {
      final var parts = new String(document.content(), StandardCharsets.UTF_8).split("\n", -1);
      return type.cast(new User(parts[0], parts[1], parts[2]));
    }
  }

  /** Document with one derived searchable field. */
  @Document("quarkus_users")
  public record User(@Id String id, @Index String country, String name) {}
}
