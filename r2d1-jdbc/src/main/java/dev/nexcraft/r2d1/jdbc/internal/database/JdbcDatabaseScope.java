package dev.nexcraft.r2d1.jdbc.internal.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Database location selected by the connection that initialized one collection. */
public interface JdbcDatabaseScope {

  @Nullable String catalog();

  @Nullable String schema();

  void requireSameDatabase(Connection connection) throws SQLException;

  String table(String collection);

  String index(String name);

  static String quoteDatabaseIdentifier(String identifier) {
    Objects.requireNonNull(identifier, "identifier");
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
