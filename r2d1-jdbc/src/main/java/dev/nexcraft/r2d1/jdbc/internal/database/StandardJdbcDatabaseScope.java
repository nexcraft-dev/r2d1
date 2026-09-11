package dev.nexcraft.r2d1.jdbc.internal.database;

import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Catalog and schema scope used by JDBC databases with standard schema metadata. */
record StandardJdbcDatabaseScope(@Nullable String catalog, String schema, String databaseLabel)
    implements JdbcDatabaseScope {

  StandardJdbcDatabaseScope {
    if (schema.isBlank()) {
      throw new IllegalArgumentException("schema must not be blank");
    }
    Objects.requireNonNull(databaseLabel, "databaseLabel");
  }

  static StandardJdbcDatabaseScope capture(Connection connection, JdbcSchemaProfile profile)
      throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(profile, "profile");
    String schema = connection.getSchema();
    if (schema == null || schema.isBlank()) {
      throw new StorageException.Operation(
          profile.databaseLabel() + " connection did not provide an active schema");
    }
    return new StandardJdbcDatabaseScope(connection.getCatalog(), schema, profile.databaseLabel());
  }

  @Override
  public void requireSameDatabase(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    if (!Objects.equals(catalog, connection.getCatalog())
        || !schema.equals(connection.getSchema())) {
      throw new StorageException.Operation(
          databaseLabel + " connection scope does not match the initialized database");
    }
  }

  @Override
  public String table(String collection) {
    return qualified(JdbcMetadata.requireIdentifier(collection, "document collection"));
  }

  @Override
  public String index(String name) {
    return qualified(JdbcMetadata.requireIdentifier(name, "index name"));
  }

  private String qualified(String name) {
    return quoteDatabaseIdentifier(schema) + "." + quoteDatabaseIdentifier(name);
  }

  private static String quoteDatabaseIdentifier(String identifier) {
    return JdbcDatabaseScope.quoteDatabaseIdentifier(identifier);
  }
}
