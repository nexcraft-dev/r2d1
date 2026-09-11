package dev.nexcraft.r2d1.jdbc.internal.database;

import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Reconciles JDBC tables and required single-column indexes without destructive changes. */
final class JdbcSchemaManager {

  private final JdbcSchemaProfile profile;

  JdbcSchemaManager(JdbcSchemaProfile profile) {
    this.profile = Objects.requireNonNull(profile, "profile");
  }

  void initialize(
      Connection connection, JdbcDatabaseScope scope, CollectionMetadata collectionMetadata)
      throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(collectionMetadata, "collectionMetadata");
    collectionMetadata.indexedFields().forEach(profile::valueType);

    Optional<TableInfo> table = inspectTable(connection, scope, collectionMetadata.collection());
    if (table.isEmpty()) {
      createTable(connection, scope, collectionMetadata);
    } else if (!profile.tableType().equals(table.orElseThrow().type())) {
      throw incompatible(collectionMetadata, "table type");
    }
    validateExistingIndexes(connection, scope, collectionMetadata);
    reconcileTable(connection, scope, collectionMetadata);
    ensureIndexes(connection, scope, collectionMetadata);
  }

  private void createTable(
      Connection connection, JdbcDatabaseScope scope, CollectionMetadata metadata)
      throws SQLException {
    List<String> definitions = new ArrayList<>();
    definitions.add(
        quoteUserIdentifier(JdbcMetadata.DOCUMENT_ID)
            + " "
            + profile.sqlType(JdbcValueType.STRING)
            + " NOT NULL PRIMARY KEY");
    for (IndexedField field : metadata.indexedFields()) {
      definitions.add(
          quoteUserIdentifier(field.name())
              + " "
              + profile.sqlType(profile.valueType(field))
              + " NOT NULL");
    }
    execute(
        connection,
        "CREATE TABLE IF NOT EXISTS "
            + scope.table(metadata.collection())
            + " ("
            + String.join(", ", definitions)
            + ")");
  }

  private void reconcileTable(
      Connection connection, JdbcDatabaseScope scope, CollectionMetadata metadata)
      throws SQLException {
    Map<String, ColumnInfo> columns = inspectColumns(connection, scope, metadata.collection());
    validateColumns(metadata, columns, false);
    validatePrimaryKey(connection, scope, metadata);

    Map<String, ColumnInfo> existingColumns = columns;
    List<IndexedField> missing =
        metadata.indexedFields().stream()
            .filter(field -> !existingColumns.containsKey(field.name()))
            .toList();
    if (!missing.isEmpty()) {
      if (hasRows(connection, scope, metadata.collection())) {
        throw new StorageException.Operation(
            profile.databaseLabel()
                + " schema requires a rebuild before adding required indexed fields to "
                + metadata.collection());
      }
      for (IndexedField field : missing) {
        execute(
            connection,
            "ALTER TABLE "
                + scope.table(metadata.collection())
                + " ADD COLUMN "
                + quoteUserIdentifier(field.name())
                + " "
                + profile.sqlType(profile.valueType(field))
                + " NOT NULL");
      }
      columns = inspectColumns(connection, scope, metadata.collection());
    }
    validateColumns(metadata, columns, true);
    validatePrimaryKey(connection, scope, metadata);
  }

  private Optional<TableInfo> inspectTable(
      Connection connection, JdbcDatabaseScope scope, String collection) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    String tablePattern = metadataPattern(metadata, collection);
    TableInfo found = null;
    try (ResultSet resultSet =
        metadata.getTables(scope.catalog(), scope.schema(), tablePattern, null)) {
      while (resultSet.next()) {
        if (!matches(scope, collection, resultSet)) {
          continue;
        }
        if (found != null) {
          throw schemaInspectionFailure("duplicate table");
        }
        found = new TableInfo(requireText(resultSet, "TABLE_TYPE", "table type"));
      }
    }
    return Optional.ofNullable(found);
  }

  private Map<String, ColumnInfo> inspectColumns(
      Connection connection, JdbcDatabaseScope scope, String collection) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    String tablePattern = metadataPattern(metadata, collection);
    Map<String, ColumnInfo> columns = new LinkedHashMap<>();
    try (ResultSet resultSet =
        metadata.getColumns(scope.catalog(), scope.schema(), tablePattern, null)) {
      while (resultSet.next()) {
        if (!matches(scope, collection, resultSet)) {
          continue;
        }
        String name = requireText(resultSet, "COLUMN_NAME", "column name");
        ColumnInfo previous =
            columns.put(
                name,
                new ColumnInfo(
                    resultSet.getInt("DATA_TYPE"),
                    resultSet.getInt("COLUMN_SIZE"),
                    resultSet.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls));
        if (previous != null) {
          throw schemaInspectionFailure("duplicate column");
        }
      }
    }
    return Map.copyOf(columns);
  }

  private void validateColumns(
      CollectionMetadata metadata, Map<String, ColumnInfo> columns, boolean requireAll) {
    ColumnInfo documentId = columns.get(JdbcMetadata.DOCUMENT_ID);
    if (documentId == null
        || !profile.isCompatibleColumn(
            JdbcValueType.STRING, documentId.jdbcType(), documentId.columnSize())
        || !documentId.notNull()) {
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
      JdbcValueType expected = profile.valueType(field);
      if (!profile.isCompatibleColumn(expected, actual.jdbcType(), actual.columnSize())
          || !actual.notNull()) {
        throw incompatible(metadata, field.name());
      }
    }
  }

  private void validatePrimaryKey(
      Connection connection, JdbcDatabaseScope scope, CollectionMetadata collectionMetadata)
      throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    Map<Short, String> columns = new TreeMap<>();
    try (ResultSet resultSet =
        metadata.getPrimaryKeys(scope.catalog(), scope.schema(), collectionMetadata.collection())) {
      while (resultSet.next()) {
        if (!matches(scope, collectionMetadata.collection(), resultSet)) {
          continue;
        }
        short position = resultSet.getShort("KEY_SEQ");
        String previous =
            columns.put(position, requireText(resultSet, "COLUMN_NAME", "primary-key column"));
        if (previous != null) {
          throw schemaInspectionFailure("duplicate primary-key position");
        }
      }
    }
    if (!List.of(JdbcMetadata.DOCUMENT_ID).equals(List.copyOf(columns.values()))) {
      throw incompatible(collectionMetadata, JdbcMetadata.DOCUMENT_ID);
    }
  }

  private static boolean hasRows(Connection connection, JdbcDatabaseScope scope, String collection)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT 1 FROM " + scope.table(collection) + " FETCH FIRST 1 ROW ONLY")) {
      return resultSet.next();
    }
  }

  private void ensureIndexes(
      Connection connection, JdbcDatabaseScope scope, CollectionMetadata metadata)
      throws SQLException {
    Map<String, List<String>> indexes = inspectIndexes(connection, scope, metadata.collection());
    for (IndexedField field : metadata.indexedFields()) {
      String indexName = physicalIndexName(metadata.collection(), field.name());
      if (!indexes.containsKey(indexName)) {
        execute(
            connection,
            "CREATE INDEX IF NOT EXISTS "
                + scope.index(indexName)
                + " ON "
                + scope.table(metadata.collection())
                + " ("
                + quoteUserIdentifier(field.name())
                + ")");
        indexes = inspectIndexes(connection, scope, metadata.collection());
      }
      if (!List.of(field.name()).equals(indexes.get(indexName))) {
        throw incompatible(metadata, "index for " + field.name());
      }
    }
  }

  private void validateExistingIndexes(
      Connection connection, JdbcDatabaseScope scope, CollectionMetadata metadata)
      throws SQLException {
    Map<String, List<String>> indexes = inspectIndexes(connection, scope, metadata.collection());
    for (IndexedField field : metadata.indexedFields()) {
      String indexName = physicalIndexName(metadata.collection(), field.name());
      if (indexes.containsKey(indexName) && !List.of(field.name()).equals(indexes.get(indexName))) {
        throw incompatible(metadata, "index for " + field.name());
      }
    }
  }

  private Map<String, List<String>> inspectIndexes(
      Connection connection, JdbcDatabaseScope scope, String collection) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    Map<String, TreeMap<Short, String>> ordered = new LinkedHashMap<>();
    try (ResultSet resultSet =
        metadata.getIndexInfo(scope.catalog(), scope.schema(), collection, false, false)) {
      while (resultSet.next()) {
        if (!matches(scope, collection, resultSet)
            || resultSet.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
          continue;
        }
        String indexName = resultSet.getString("INDEX_NAME");
        String columnName = resultSet.getString("COLUMN_NAME");
        if (indexName == null || columnName == null) {
          continue;
        }
        short position = resultSet.getShort("ORDINAL_POSITION");
        String previous =
            ordered
                .computeIfAbsent(indexName, ignored -> new TreeMap<>())
                .put(position, columnName);
        if (previous != null) {
          throw schemaInspectionFailure("duplicate index position");
        }
      }
    }
    Map<String, List<String>> indexes = new LinkedHashMap<>();
    ordered.forEach((name, columns) -> indexes.put(name, List.copyOf(columns.values())));
    return Map.copyOf(indexes);
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private static boolean matches(JdbcDatabaseScope scope, String collection, ResultSet resultSet)
      throws SQLException {
    return Objects.equals(scope.catalog(), resultSet.getString("TABLE_CAT"))
        && scope.schema().equals(resultSet.getString("TABLE_SCHEM"))
        && collection.equals(resultSet.getString("TABLE_NAME"));
  }

  private static String metadataPattern(DatabaseMetaData metadata, String identifier)
      throws SQLException {
    String escape = metadata.getSearchStringEscape();
    if (escape == null || escape.isEmpty()) {
      return identifier;
    }
    return identifier
        .replace(escape, escape + escape)
        .replace("_", escape + "_")
        .replace("%", escape + "%");
  }

  private static String quoteUserIdentifier(String identifier) {
    return JdbcDatabaseScope.quoteDatabaseIdentifier(
        JdbcMetadata.requireIdentifier(identifier, "identifier"));
  }

  private static String physicalIndexName(String collection, String field) {
    return JdbcMetadata.requireIdentifier("idx_" + collection + "_" + field, "physical index name");
  }

  private String requireText(ResultSet resultSet, String column, String description)
      throws SQLException {
    String value = resultSet.getString(column);
    if (value == null || value.isBlank()) {
      throw schemaInspectionFailure("invalid " + description);
    }
    return value;
  }

  private StorageException.Operation schemaInspectionFailure(String detail) {
    return new StorageException.Operation(
        profile.databaseLabel() + " schema inspection returned " + detail);
  }

  private StorageException.Operation incompatible(CollectionMetadata metadata, String detail) {
    return new StorageException.Operation(
        profile.databaseLabel()
            + " schema is incompatible for collection "
            + metadata.collection()
            + " at "
            + detail);
  }

  private record TableInfo(String type) {}

  private record ColumnInfo(int jdbcType, int columnSize, boolean notNull) {}
}
