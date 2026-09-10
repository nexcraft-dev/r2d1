package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.jdbc.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.jdbc.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** H2 implementation of the internal JDBC index dialect. */
final class H2Dialect implements JdbcDialect {

  private final H2SchemaManager schemaManager = new H2SchemaManager();
  private volatile @Nullable H2DatabaseScope databaseScope;

  @Override
  public void initialize(Connection connection, CollectionMetadata metadata) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(metadata, "metadata");
    metadata.indexedFields().forEach(H2ValueType::from);
    H2DatabaseScope scope = H2DatabaseScope.capture(connection);
    schemaManager.initialize(connection, scope, metadata);
    databaseScope = scope;
  }

  @Override
  public void clear(Connection connection, CollectionMetadata metadata) throws SQLException {
    H2DatabaseScope scope = requireScope(connection);
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM " + scope.table(metadata.collection()))) {
      statement.executeUpdate();
    }
  }

  @Override
  public void upsert(Connection connection, CollectionMetadata metadata, IndexEntry entry)
      throws SQLException {
    H2DatabaseScope scope = requireScope(connection);
    requireCollection(metadata, entry.documentKey().collection());
    validateEntry(metadata, entry.values());

    List<String> columns = new ArrayList<>();
    List<String> placeholders = new ArrayList<>();
    columns.add(quoteIdentifier(JdbcMetadata.DOCUMENT_ID));
    placeholders.add("?");
    for (IndexedField field : metadata.indexedFields()) {
      columns.add(quoteIdentifier(field.name()));
      placeholders.add("?");
    }
    String sql =
        "MERGE INTO "
            + scope.table(metadata.collection())
            + " ("
            + String.join(", ", columns)
            + ") KEY ("
            + quoteIdentifier(JdbcMetadata.DOCUMENT_ID)
            + ") VALUES ("
            + String.join(", ", placeholders)
            + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, entry.documentKey().id());
      int parameterIndex = 2;
      for (IndexedField field : metadata.indexedFields()) {
        H2ValueType.from(field)
            .bind(statement, parameterIndex++, field.name(), entry.values().get(field.name()));
      }
      statement.executeUpdate();
    }
  }

  @Override
  public IndexPage query(Connection connection, CollectionMetadata metadata, IndexQuery query)
      throws SQLException {
    H2DatabaseScope scope = requireScope(connection);
    requireCollection(metadata, query.collection());

    List<Parameter> parameters = new ArrayList<>();
    List<String> conditions = new ArrayList<>();
    for (IndexQuery.Filter filter : query.filters()) {
      IndexedField field = metadata.requireIndexedField(filter.indexedField());
      H2ValueType type = H2ValueType.from(field);
      requireOperator(field, filter.operator());
      type.requireCompatible(field.name(), filter.value());
      conditions.add(quoteIdentifier(field.name()) + " " + operatorSql(filter.operator()) + " ?");
      parameters.add(valueParameter(type, field.name(), filter.value()));
    }

    IndexedField sortField =
        query.sort().map(sort -> metadata.requireIndexedField(sort.indexedField())).orElse(null);
    if (sortField != null) {
      H2ValueType.from(sortField);
    }
    if (query.cursor().isPresent()) {
      conditions.add(cursorCondition(metadata, query, sortField, parameters));
    }

    StringBuilder sql = new StringBuilder("SELECT ");
    sql.append(quoteIdentifier(JdbcMetadata.DOCUMENT_ID));
    if (sortField != null) {
      sql.append(", ").append(quoteIdentifier(sortField.name()));
    }
    sql.append(" FROM ").append(scope.table(metadata.collection()));
    if (!conditions.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", conditions));
    }

    SortDirection direction =
        query.sort().map(IndexQuery.Sort::direction).orElse(SortDirection.ASC);
    if (sortField == null) {
      sql.append(" ORDER BY ").append(quoteIdentifier(JdbcMetadata.DOCUMENT_ID)).append(" ASC");
    } else {
      sql.append(" ORDER BY ")
          .append(quoteIdentifier(sortField.name()))
          .append(' ')
          .append(direction.name())
          .append(", ")
          .append(quoteIdentifier(JdbcMetadata.DOCUMENT_ID))
          .append(' ')
          .append(direction.name());
    }
    sql.append(" LIMIT ?");
    parameters.add(longParameter((long) query.limit() + 1L));

    List<QueryRow> rows = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      bind(statement, parameters);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          String documentId = resultSet.getString(1);
          if (documentId == null) {
            throw new StorageException.Operation("H2 query returned an invalid document_id");
          }
          DocumentKey key;
          try {
            key = new DocumentKey(metadata.collection(), documentId);
          } catch (IllegalArgumentException | NullPointerException failure) {
            throw new StorageException.Operation(
                "H2 query returned an invalid document_id", failure);
          }
          @Nullable IndexValue sortValue =
              sortField == null
                  ? null
                  : H2ValueType.from(sortField).read(resultSet, 2, sortField.name());
          rows.add(new QueryRow(key, sortValue));
        }
      }
    }
    return page(rows, query.limit(), sortField != null);
  }

  @Override
  public void delete(Connection connection, CollectionMetadata metadata, DocumentKey key)
      throws SQLException {
    H2DatabaseScope scope = requireScope(connection);
    requireCollection(metadata, key.collection());
    String sql =
        "DELETE FROM "
            + scope.table(metadata.collection())
            + " WHERE "
            + quoteIdentifier(JdbcMetadata.DOCUMENT_ID)
            + " = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, key.id());
      statement.executeUpdate();
    }
  }

  @Override
  public StorageException translate(String operation, SQLException failure) {
    if (failure instanceof SQLTimeoutException
        || failure instanceof SQLTransactionRollbackException) {
      return new StorageException.Unavailable("JDBC " + operation + " failed", failure);
    }
    return JdbcDialect.super.translate(operation, failure);
  }

  private H2DatabaseScope requireScope(Connection connection) throws SQLException {
    H2DatabaseScope scope = databaseScope;
    if (scope == null) {
      throw new StorageException.Operation("H2 dialect is not initialized");
    }
    scope.requireSameDatabase(connection);
    return scope;
  }

  private static void validateEntry(CollectionMetadata metadata, Map<String, IndexValue> values) {
    for (String supplied : values.keySet()) {
      metadata.requireIndexedField(supplied);
    }
    for (IndexedField field : metadata.indexedFields()) {
      IndexValue value = values.get(field.name());
      if (value == null) {
        throw new IllegalArgumentException(
            "indexed field value must not be null or missing: " + field.name());
      }
      H2ValueType.from(field).requireCompatible(field.name(), value);
    }
  }

  private static String cursorCondition(
      CollectionMetadata metadata,
      IndexQuery query,
      @Nullable IndexedField sortField,
      List<Parameter> parameters) {
    IndexCursor cursor = query.cursor().orElseThrow();
    requireCollection(metadata, cursor.lastDocumentKey().collection());
    String documentId = quoteIdentifier(JdbcMetadata.DOCUMENT_ID);
    if (sortField == null) {
      if (cursor.sortValue().isPresent()) {
        throw new IllegalArgumentException("unsorted query cursor must not include a sort value");
      }
      parameters.add(stringParameter(cursor.lastDocumentKey().id()));
      return documentId + " > ?";
    }

    IndexValue sortValue =
        cursor
            .sortValue()
            .orElseThrow(
                () ->
                    new IllegalArgumentException("sorted query cursor must include a sort value"));
    H2ValueType type = H2ValueType.from(sortField);
    type.requireCompatible(sortField.name(), sortValue);
    String comparison = query.sort().orElseThrow().direction() == SortDirection.ASC ? ">" : "<";
    String column = quoteIdentifier(sortField.name());
    parameters.add(valueParameter(type, sortField.name(), sortValue));
    parameters.add(valueParameter(type, sortField.name(), sortValue));
    parameters.add(stringParameter(cursor.lastDocumentKey().id()));
    return "("
        + column
        + " "
        + comparison
        + " ? OR ("
        + column
        + " = ? AND "
        + documentId
        + " "
        + comparison
        + " ?))";
  }

  private static IndexPage page(List<QueryRow> rows, int requestedLimit, boolean sorted) {
    int returnedCount = Math.min(rows.size(), requestedLimit);
    List<DocumentKey> keys =
        rows.subList(0, returnedCount).stream().map(QueryRow::documentKey).toList();
    Optional<IndexCursor> nextCursor = Optional.empty();
    if (rows.size() > requestedLimit && returnedCount > 0) {
      QueryRow last = rows.get(returnedCount - 1);
      Optional<IndexValue> sortValue =
          sorted
              ? Optional.of(
                  Objects.requireNonNull(last.sortValue(), "sorted H2 row has no sort value"))
              : Optional.empty();
      nextCursor = Optional.of(new IndexCursor(last.documentKey(), sortValue));
    }
    return new IndexPage(keys, nextCursor);
  }

  private static void requireOperator(IndexedField field, ComparisonOperator operator) {
    if (field.type() == JdbcMetadata.ValueType.BOOLEAN
        && operator != ComparisonOperator.EQUAL
        && operator != ComparisonOperator.NOT_EQUAL) {
      throw new IllegalArgumentException(
          "Boolean indexed field supports only equality comparisons: " + field.name());
    }
  }

  private static String operatorSql(ComparisonOperator operator) {
    return switch (operator) {
      case EQUAL -> "=";
      case NOT_EQUAL -> "<>";
      case GREATER_THAN -> ">";
      case GREATER_THAN_OR_EQUAL -> ">=";
      case LESS_THAN -> "<";
      case LESS_THAN_OR_EQUAL -> "<=";
    };
  }

  private static void requireCollection(CollectionMetadata metadata, String actualCollection) {
    if (!metadata.collection().equals(actualCollection)) {
      throw new IllegalArgumentException(
          "collection does not match initialized H2 metadata: " + actualCollection);
    }
  }

  private static String quoteIdentifier(String identifier) {
    return H2DatabaseScope.quoteDatabaseIdentifier(
        JdbcMetadata.requireIdentifier(identifier, "identifier"));
  }

  private static Parameter valueParameter(H2ValueType type, String fieldName, IndexValue value) {
    return (statement, index) -> type.bind(statement, index, fieldName, value);
  }

  private static Parameter stringParameter(String value) {
    return (statement, index) -> statement.setString(index, value);
  }

  private static Parameter longParameter(long value) {
    return (statement, index) -> statement.setLong(index, value);
  }

  private static void bind(PreparedStatement statement, List<Parameter> parameters)
      throws SQLException {
    for (int index = 0; index < parameters.size(); index++) {
      parameters.get(index).bind(statement, index + 1);
    }
  }

  @FunctionalInterface
  private interface Parameter {

    void bind(PreparedStatement statement, int index) throws SQLException;
  }

  private record QueryRow(DocumentKey documentKey, @Nullable IndexValue sortValue) {}
}
