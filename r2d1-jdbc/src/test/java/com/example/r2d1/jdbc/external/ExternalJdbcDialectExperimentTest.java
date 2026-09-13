package com.example.r2d1.jdbc.external;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.jdbc.JdbcExecution;
import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDatabase;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Verifies whether the current internal dialect boundary is usable from a third-party package. */
class ExternalJdbcDialectExperimentTest {

  @Test
  void externalImplementationCanImplementTheVisibleBridgeWithoutBuiltInHelpers()
      throws SQLException {
    ExternalJdbcDialect dialect = new ExternalJdbcDialect();
    Connection connection = unusedConnection();
    CollectionMetadata metadata = JdbcMetadata.inspect(ExternalDocument.class);
    DocumentKey key = new DocumentKey("external_entries", "one");
    IndexEntry entry = new IndexEntry(key, Map.of("rank", new IndexValue.LongValue(1L)));
    IndexQuery query =
        new IndexQuery("external_entries", List.of(), Optional.empty(), 10, Optional.empty());

    dialect.initialize(connection, metadata);
    dialect.upsert(connection, metadata, entry);
    dialect.query(connection, metadata, query);
    dialect.delete(connection, metadata, key);
    dialect.clear(connection, metadata);

    assertThat(dialect.operations())
        .containsExactly("initialize", "upsert", "query", "delete", "clear");
  }

  @Test
  void visibleBridgeUsesInternalMetadataAndDoesNotExposeTheDialectType()
      throws ClassNotFoundException {
    assertThat(Modifier.isPublic(JdbcDatabase.class.getModifiers())).isTrue();
    assertThat(JdbcDatabase.class.getPackageName())
        .isEqualTo("dev.nexcraft.r2d1.jdbc.internal.database");
    assertThat(CollectionMetadata.class.getPackageName())
        .isEqualTo("dev.nexcraft.r2d1.jdbc.internal.metadata");

    Class<?> dialectType = Class.forName("dev.nexcraft.r2d1.jdbc.internal.database.JdbcDialect");
    assertThat(Modifier.isPublic(dialectType.getModifiers())).isFalse();
  }

  @Test
  void publicStoreHasNoCustomDialectRegistrationSurface() {
    Constructor<?>[] constructors = JdbcIndexStore.class.getConstructors();

    assertThat(constructors).hasSize(1);
    assertThat(constructors[0].getParameterTypes())
        .containsExactly(DataSource.class, JdbcExecution.class);
  }

  @Test
  void defaultBridgeTranslationIsAvailableButStillUsesCoreFailureCategories() {
    SQLException cause = new SQLException("native external detail", "08006");

    StorageException translated = new ExternalJdbcDialect().translate("query", cause);

    assertThat(translated)
        .isInstanceOf(StorageException.Unavailable.class)
        .hasMessage("JDBC query failed")
        .hasCause(cause);
  }

  private static Connection unusedConnection() {
    return (Connection)
        Proxy.newProxyInstance(
            ExternalJdbcDialectExperimentTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              throw new UnsupportedOperationException("external fixture does not use JDBC");
            });
  }

  @Document("external_entries")
  private static final class ExternalDocument {
    @Index private long rank;
  }
}
