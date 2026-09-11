package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Catalog and schema selected by the connection that initialized one collection. */
record JdbcDatabaseScope(@Nullable String catalog, String schema, String databaseLabel) {

  JdbcDatabaseScope {
    if (schema.isBlank()) {
      throw new IllegalArgumentException("schema must not be blank");
    }
    Objects.requireNonNull(databaseLabel, "databaseLabel");
  }

  static JdbcDatabaseScope capture(Connection connection, JdbcSchemaProfile profile)
      throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(profile, "profile");
    String schema = connection.getSchema();
    if (schema == null || schema.isBlank()) {
      throw new StorageException.Operation(
          profile.databaseLabel() + " connection did not provide an active schema");
    }
    return new JdbcDatabaseScope(connection.getCatalog(), schema, profile.databaseLabel());
  }

  void requireSameDatabase(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    if (!Objects.equals(catalog, connection.getCatalog())
        || !schema.equals(connection.getSchema())) {
      throw new StorageException.Operation(
          databaseLabel + " connection scope does not match the initialized database");
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
