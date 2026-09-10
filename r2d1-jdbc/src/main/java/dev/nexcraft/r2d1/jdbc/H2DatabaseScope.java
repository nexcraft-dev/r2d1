package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Catalog and schema selected by the H2 connection that initialized one collection. */
record H2DatabaseScope(@Nullable String catalog, String schema) {

  H2DatabaseScope {
    if (schema.isBlank()) {
      throw new IllegalArgumentException("schema must not be blank");
    }
  }

  static H2DatabaseScope capture(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    String schema = connection.getSchema();
    if (schema == null || schema.isBlank()) {
      throw new StorageException.Operation("H2 connection did not provide an active schema");
    }
    return new H2DatabaseScope(connection.getCatalog(), schema);
  }

  void requireSameDatabase(Connection connection) throws SQLException {
    if (!Objects.equals(catalog, connection.getCatalog())) {
      throw new StorageException.Operation(
          "H2 connection catalog does not match the initialized database");
    }
  }

  String table(String collection) {
    return qualified(JdbcMetadata.requireIdentifier(collection, "document collection"));
  }

  String index(String name) {
    return qualified(JdbcMetadata.requireIdentifier(name, "index name"));
  }

  private String qualified(String name) {
    return quoteDatabaseIdentifier(schema) + "." + quoteDatabaseIdentifier(name);
  }

  static String quoteDatabaseIdentifier(String identifier) {
    Objects.requireNonNull(identifier, "identifier");
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
