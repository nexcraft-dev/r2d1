package dev.nexcraft.r2d1.jdbc.internal.database.sqlite;

import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDatabaseScope;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcSchemaProfile;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcValueType;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcWriteCoordinator;
import dev.nexcraft.r2d1.jdbc.internal.database.StandardJdbcDialect;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite implementation of the internal JDBC index dialect. */
public final class SqliteDialect extends StandardJdbcDialect {

  static final int BUSY_TIMEOUT_MILLIS = 5_000;

  private static final int SQLITE_PERM = 3;
  private static final int SQLITE_BUSY = 5;
  private static final int SQLITE_LOCKED = 6;
  private static final int SQLITE_READONLY = 8;
  private static final int SQLITE_AUTH = 23;

  public SqliteDialect(JdbcWriteCoordinator writeCoordinator) {
    super(JdbcSchemaProfile.SQLITE, new SqliteSchemaManager(), writeCoordinator);
  }

  @Override
  protected JdbcDatabaseScope captureScope(Connection connection) throws SQLException {
    return SqliteDatabaseScope.capture(connection);
  }

  @Override
  protected void prepareConnection(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
    }
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("PRAGMA busy_timeout")) {
      if (!resultSet.next() || resultSet.getInt(1) != BUSY_TIMEOUT_MILLIS) {
        throw new StorageException.Operation("SQLite busy timeout could not be configured");
      }
    }
  }

  @Override
  public void upsert(Connection connection, CollectionMetadata metadata, IndexEntry entry)
      throws SQLException {
    JdbcDatabaseScope scope = requireScope(connection);
    requireCollection(metadata, entry.documentKey().collection());
    validateEntry(metadata, entry.values());

    List<String> columns = new ArrayList<>();
    List<String> placeholders = new ArrayList<>();
    List<String> updates = new ArrayList<>();
    String documentId = quoteIdentifier(JdbcMetadata.DOCUMENT_ID);
    columns.add(documentId);
    placeholders.add("?");
    for (IndexedField field : metadata.indexedFields()) {
      String column = quoteIdentifier(field.name());
      columns.add(column);
      placeholders.add("?");
      updates.add(column + " = excluded." + column);
    }
    if (updates.isEmpty()) {
      updates.add(documentId + " = excluded." + documentId);
    }

    String sql =
        "INSERT INTO "
            + scope.table(metadata.collection())
            + " ("
            + String.join(", ", columns)
            + ") VALUES ("
            + String.join(", ", placeholders)
            + ") ON CONFLICT("
            + documentId
            + ") DO UPDATE SET "
            + String.join(", ", updates);
    executeWrite(
        () -> {
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.documentKey().id());
            int parameterIndex = 2;
            for (IndexedField field : metadata.indexedFields()) {
              JdbcValueType type = profile().valueType(field);
              bindValue(
                  statement,
                  parameterIndex++,
                  type,
                  field.name(),
                  entry.values().get(field.name()));
            }
            statement.executeUpdate();
          }
        });
  }

  @Override
  protected void bindValue(
      PreparedStatement statement,
      int parameterIndex,
      JdbcValueType type,
      String fieldName,
      IndexValue value)
      throws SQLException {
    if (type != JdbcValueType.BOOLEAN) {
      super.bindValue(statement, parameterIndex, type, fieldName, value);
      return;
    }
    type.requireCompatible(fieldName, value);
    statement.setInt(parameterIndex, ((IndexValue.BooleanValue) value).value() ? 1 : 0);
  }

  @Override
  protected IndexValue readValue(
      ResultSet resultSet, int columnIndex, JdbcValueType type, String fieldName)
      throws SQLException {
    if (type != JdbcValueType.BOOLEAN) {
      return super.readValue(resultSet, columnIndex, type, fieldName);
    }
    Object raw = resultSet.getObject(columnIndex);
    if (!(raw instanceof Number number)) {
      throw invalidBoolean(fieldName);
    }
    long value = number.longValue();
    if ((value != 0L && value != 1L) || number.doubleValue() != value) {
      throw invalidBoolean(fieldName);
    }
    return new IndexValue.BooleanValue(value == 1L);
  }

  @Override
  public StorageException translate(String operation, SQLException failure) {
    int primaryCode = failure.getErrorCode() & 0xff;
    if (primaryCode == SQLITE_BUSY || primaryCode == SQLITE_LOCKED) {
      return new StorageException.Unavailable("JDBC " + operation + " failed", failure);
    }
    if (primaryCode == SQLITE_PERM
        || primaryCode == SQLITE_READONLY
        || primaryCode == SQLITE_AUTH) {
      return new StorageException.Access("JDBC " + operation + " failed", failure);
    }
    return super.translate(operation, failure);
  }

  private static StorageException.Operation invalidBoolean(String fieldName) {
    return new StorageException.Operation(
        "SQLite returned an invalid value for indexed field '" + fieldName + "'");
  }
}
