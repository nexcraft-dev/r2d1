package dev.nexcraft.r2d1.jdbc.internal.database.sqlite;

import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDatabaseScope;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcSchema;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcSchemaProfile;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcValueType;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Reconciles SQLite tables through SQLite schema and PRAGMA metadata. */
final class SqliteSchemaManager implements JdbcSchema {

  private static final JdbcSchemaProfile PROFILE = JdbcSchemaProfile.SQLITE;

  @Override
  public void initialize(
      Connection connection, JdbcDatabaseScope scope, CollectionMetadata collectionMetadata)
      throws SQLException {
    Objects.requireNonNull(connection, "connection");
    if (!(Objects.requireNonNull(scope, "scope") instanceof SqliteDatabaseScope sqliteScope)) {
      throw new StorageException.Operation("SQLite schema received an incompatible database scope");
    }
    Objects.requireNonNull(collectionMetadata, "collectionMetadata");
    collectionMetadata.indexedFields().forEach(PROFILE::valueType);

    Optional<TableInfo> table = inspectTable(connection, collectionMetadata.collection());
    if (table.isEmpty()) {
      createTable(connection, sqliteScope, collectionMetadata);
    } else if (!"table".equals(table.orElseThrow().type())) {
      throw incompatible(collectionMetadata, "table type");
    }
    validateExistingIndexes(connection, collectionMetadata);
    reconcileTable(connection, sqliteScope, collectionMetadata);
    ensureIndexes(connection, sqliteScope, collectionMetadata);
  }

  private static Optional<TableInfo> inspectTable(Connection connection, String collection)
      throws SQLException {
    TableInfo found = null;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT type FROM main.sqlite_schema WHERE name = ? AND type IN ('table', 'view')")) {
      statement.setString(1, collection);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          if (found != null) {
            throw schemaInspectionFailure("duplicate table");
          }
          found = new TableInfo(requireText(resultSet, "type", "table type"));
        }
      }
    }
    return Optional.ofNullable(found);
  }

  private static void createTable(
      Connection connection, SqliteDatabaseScope scope, CollectionMetadata metadata)
      throws SQLException {
    List<String> definitions = new ArrayList<>();
    definitions.add(
        quote(JdbcMetadata.DOCUMENT_ID)
            + " "
            + PROFILE.sqlType(JdbcValueType.STRING)
            + " NOT NULL PRIMARY KEY");
    for (IndexedField field : metadata.indexedFields()) {
      definitions.add(
          quote(field.name()) + " " + PROFILE.sqlType(PROFILE.valueType(field)) + " NOT NULL");
    }
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS "
            + scope.table(metadata.collection())
            + " ("
            + String.join(", ", definitions)
            + ")");
  }

  private static void reconcileTable(
      Connection connection, SqliteDatabaseScope scope, CollectionMetadata metadata)
      throws SQLException {
    Map<String, ColumnInfo> columns = inspectColumns(connection, metadata.collection());
    validateColumns(metadata, columns, false);
    validatePrimaryKey(metadata, columns);

    Map<String, ColumnInfo> existingColumns = columns;
    List<IndexedField> missing =
        metadata.indexedFields().stream()
            .filter(field -> !existingColumns.containsKey(field.name()))
            .toList();
    if (!missing.isEmpty()) {
      if (hasRows(connection, scope, metadata.collection())) {
        throw new StorageException.Operation(
            "SQLite schema requires a rebuild before adding required indexed fields to "
                + metadata.collection());
      }
      for (IndexedField field : missing) {
        JdbcValueType type = PROFILE.valueType(field);
        execute(
            connection,
            "ALTER TABLE "
                + scope.table(metadata.collection())
                + " ADD COLUMN "
                + quote(field.name())
                + " "
                + PROFILE.sqlType(type)
                + " NOT NULL DEFAULT "
                + additiveDefault(type));
      }
      columns = inspectColumns(connection, metadata.collection());
    }
    validateColumns(metadata, columns, true);
    validatePrimaryKey(metadata, columns);
  }

  private static Map<String, ColumnInfo> inspectColumns(Connection connection, String collection)
      throws SQLException {
    Map<String, ColumnInfo> columns = new LinkedHashMap<>();
    String sql = "PRAGMA main.table_xinfo(" + quote(collection) + ")";
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        String name = requireText(resultSet, "name", "column name");
        ColumnInfo previous =
            columns.put(
                name,
                new ColumnInfo(
                    normalizedType(resultSet.getString("type")),
                    resultSet.getInt("notnull") == 1,
                    resultSet.getInt("pk"),
                    resultSet.getInt("hidden")));
        if (previous != null) {
          throw schemaInspectionFailure("duplicate column");
        }
      }
    }
    return Map.copyOf(columns);
  }

  private static void validateColumns(
      CollectionMetadata metadata, Map<String, ColumnInfo> columns, boolean requireAll) {
    ColumnInfo documentId = columns.get(JdbcMetadata.DOCUMENT_ID);
    if (!compatibleColumn(documentId, JdbcValueType.STRING) || documentId.primaryKeyOrder() != 1) {
      throw incompatible(metadata, JdbcMetadata.DOCUMENT_ID);
    }
    for (IndexedField field : metadata.indexedFields()) {
      ColumnInfo actual = columns.get(field.name());
      if (actual == null) {
        if (requireAll) {
          throw incompatible(metadata, field.name());
        }
        continue;
      }
      if (!compatibleColumn(actual, PROFILE.valueType(field)) || actual.primaryKeyOrder() != 0) {
        throw incompatible(metadata, field.name());
      }
    }
  }

  private static boolean compatibleColumn(ColumnInfo actual, JdbcValueType type) {
    return actual != null
        && PROFILE.sqlType(type).equals(actual.declaredType())
        && actual.notNull()
        && actual.hidden() == 0;
  }

  private static void validatePrimaryKey(
      CollectionMetadata metadata, Map<String, ColumnInfo> columns) {
    Map<Integer, String> primaryKey = new TreeMap<>();
    columns.forEach(
        (name, column) -> {
          if (column.primaryKeyOrder() > 0) {
            primaryKey.put(column.primaryKeyOrder(), name);
          }
        });
    if (!List.of(JdbcMetadata.DOCUMENT_ID).equals(List.copyOf(primaryKey.values()))) {
      throw incompatible(metadata, JdbcMetadata.DOCUMENT_ID);
    }
  }

  private static boolean hasRows(
      Connection connection, SqliteDatabaseScope scope, String collection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery("SELECT 1 FROM " + scope.table(collection) + " LIMIT 1")) {
      return resultSet.next();
    }
  }

  private static void validateExistingIndexes(Connection connection, CollectionMetadata metadata)
      throws SQLException {
    Map<String, IndexInfo> indexes = inspectIndexes(connection, metadata.collection());
    for (IndexedField field : metadata.indexedFields()) {
      String indexName = physicalIndexName(metadata.collection(), field.name());
      IndexInfo index = indexes.get(indexName);
      if (index != null && !compatibleIndex(index, field.name())) {
        throw incompatible(metadata, "index for " + field.name());
      }
    }
  }

  private static void ensureIndexes(
      Connection connection, SqliteDatabaseScope scope, CollectionMetadata metadata)
      throws SQLException {
    Map<String, IndexInfo> indexes = inspectIndexes(connection, metadata.collection());
    for (IndexedField field : metadata.indexedFields()) {
      String indexName = physicalIndexName(metadata.collection(), field.name());
      if (!indexes.containsKey(indexName)) {
        execute(
            connection,
            "CREATE INDEX IF NOT EXISTS "
                + scope.index(indexName)
                + " ON "
                + SqliteDatabaseScope.unqualified(metadata.collection())
                + " ("
                + quote(field.name())
                + ")");
        indexes = inspectIndexes(connection, metadata.collection());
      }
      if (!compatibleIndex(indexes.get(indexName), field.name())) {
        throw incompatible(metadata, "index for " + field.name());
      }
    }
  }

  private static Map<String, IndexInfo> inspectIndexes(Connection connection, String collection)
      throws SQLException {
    List<IndexDefinition> definitions = new ArrayList<>();
    String sql = "PRAGMA main.index_list(" + quote(collection) + ")";
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        String name = requireText(resultSet, "name", "index name");
        definitions.add(
            new IndexDefinition(
                name, resultSet.getInt("unique") == 1, resultSet.getInt("partial") == 1));
      }
    }
    Map<String, IndexInfo> indexes = new LinkedHashMap<>();
    for (IndexDefinition definition : definitions) {
      IndexInfo previous =
          indexes.put(
              definition.name(),
              new IndexInfo(
                  inspectIndexColumns(connection, definition.name()),
                  definition.unique(),
                  definition.partial()));
      if (previous != null) {
        throw schemaInspectionFailure("duplicate index");
      }
    }
    return Map.copyOf(indexes);
  }

  private static List<String> inspectIndexColumns(Connection connection, String index)
      throws SQLException {
    Map<Integer, String> columns = new TreeMap<>();
    String sql = "PRAGMA main.index_info(" + quote(index) + ")";
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        int position = resultSet.getInt("seqno");
        String name = resultSet.getString("name");
        String previous = columns.put(position, name == null ? "<expression>" : name);
        if (previous != null) {
          throw schemaInspectionFailure("duplicate index position");
        }
      }
    }
    return List.copyOf(columns.values());
  }

  private static boolean compatibleIndex(IndexInfo index, String field) {
    return index != null
        && !index.unique()
        && !index.partial()
        && List.of(field).equals(index.columns());
  }

  private static String additiveDefault(JdbcValueType type) {
    return switch (type) {
      case STRING -> "''";
      case LONG, BOOLEAN -> "0";
      case DOUBLE -> "0.0";
    };
  }

  private static String normalizedType(String declaration) {
    return declaration == null ? "" : declaration.trim().toUpperCase(Locale.ROOT);
  }

  private static String physicalIndexName(String collection, String field) {
    return JdbcMetadata.requireIdentifier("idx_" + collection + "_" + field, "physical index name");
  }

  private static String quote(String identifier) {
    return JdbcDatabaseScope.quoteDatabaseIdentifier(
        JdbcMetadata.requireIdentifier(identifier, "SQLite identifier"));
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private static String requireText(ResultSet resultSet, String column, String description)
      throws SQLException {
    String value = resultSet.getString(column);
    if (value == null || value.isBlank()) {
      throw schemaInspectionFailure("invalid " + description);
    }
    return value;
  }

  private static StorageException.Operation incompatible(
      CollectionMetadata metadata, String detail) {
    return new StorageException.Operation(
        "SQLite schema for " + metadata.collection() + " is incompatible: " + detail);
  }

  private static StorageException.Operation schemaInspectionFailure(String detail) {
    return new StorageException.Operation("SQLite schema inspection failed: " + detail);
  }

  private record TableInfo(String type) {}

  private record ColumnInfo(
      String declaredType, boolean notNull, int primaryKeyOrder, int hidden) {}

  private record IndexInfo(List<String> columns, boolean unique, boolean partial) {

    private IndexInfo {
      columns = List.copyOf(columns);
    }
  }

  private record IndexDefinition(String name, boolean unique, boolean partial) {}
}
