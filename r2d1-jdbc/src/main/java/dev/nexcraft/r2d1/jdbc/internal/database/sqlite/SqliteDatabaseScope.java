package dev.nexcraft.r2d1.jdbc.internal.database.sqlite;

import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDatabaseScope;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import dev.nexcraft.r2d1.spi.StorageException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** File-backed SQLite {@code main} database selected during collection initialization. */
record SqliteDatabaseScope(Path databaseFile) implements JdbcDatabaseScope {

  SqliteDatabaseScope {
    Objects.requireNonNull(databaseFile, "databaseFile");
  }

  static SqliteDatabaseScope capture(Connection connection) throws SQLException {
    return new SqliteDatabaseScope(mainDatabaseFile(connection));
  }

  @Override
  public @Nullable String catalog() {
    return null;
  }

  @Override
  public @Nullable String schema() {
    return null;
  }

  @Override
  public void requireSameDatabase(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    if (!databaseFile.equals(mainDatabaseFile(connection))) {
      throw new StorageException.Operation(
          "SQLite connection scope does not match the initialized database");
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

  static String unqualified(String identifier) {
    return JdbcDatabaseScope.quoteDatabaseIdentifier(
        JdbcMetadata.requireIdentifier(identifier, "SQLite identifier"));
  }

  private String qualified(String name) {
    return JdbcDatabaseScope.quoteDatabaseIdentifier("main")
        + "."
        + JdbcDatabaseScope.quoteDatabaseIdentifier(name);
  }

  private static Path mainDatabaseFile(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    String file = null;
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("PRAGMA database_list")) {
      while (resultSet.next()) {
        if (!"main".equals(resultSet.getString("name"))) {
          continue;
        }
        if (file != null) {
          throw new StorageException.Operation("SQLite reported duplicate main databases");
        }
        file = resultSet.getString("file");
      }
    }
    if (file == null || file.isBlank() || ":memory:".equals(file)) {
      throw new StorageException.Operation(
          "SQLite requires a persistent local file-backed main database");
    }
    try {
      return Path.of(file).toAbsolutePath().normalize();
    } catch (InvalidPathException failure) {
      throw new StorageException.Operation("SQLite database file could not be resolved", failure);
    }
  }
}
