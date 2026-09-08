package dev.nexcraft.r2d1.d1;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/** Compiles D1/SQLite CRUD and keyset queries and maps results to the core SPI. */
final class D1SqlCompiler {

  D1Statement clear(D1CollectionMetadata metadata) {
    Objects.requireNonNull(metadata, "metadata");
    return D1Statement.of("DELETE FROM " + D1Metadata.quoteIdentifier(metadata.collection()));
  }

  D1Statement upsert(D1CollectionMetadata metadata, IndexEntry entry) {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(entry, "entry");
    requireCollection(metadata, entry.documentKey().collection());
    validateEntry(metadata, entry.values());

    List<String> columns = new ArrayList<>();
    List<String> placeholders = new ArrayList<>();
    List<D1Parameter> parameters = new ArrayList<>();
    columns.add(D1Metadata.quoteIdentifier(D1Metadata.DOCUMENT_ID));
    placeholders.add(
        addParameter(parameters, new D1Parameter.TextParameter(entry.documentKey().id())));
    for (D1IndexedField field : metadata.indexedFields()) {
      columns.add(D1Metadata.quoteIdentifier(field.name()));
      placeholders.add(
          addParameter(
              parameters, field.type().parameter(field.name(), entry.values().get(field.name()))));
    }

    StringBuilder sql =
        new StringBuilder("INSERT INTO ")
            .append(D1Metadata.quoteIdentifier(metadata.collection()))
            .append(" (")
            .append(String.join(", ", columns))
            .append(") VALUES (")
            .append(String.join(", ", placeholders))
            .append(") ON CONFLICT(")
            .append(D1Metadata.quoteIdentifier(D1Metadata.DOCUMENT_ID))
            .append(") ");
    if (metadata.indexedFields().isEmpty()) {
      sql.append("DO NOTHING");
    } else {
      StringJoiner assignments = new StringJoiner(", ");
      for (D1IndexedField field : metadata.indexedFields()) {
        String quoted = D1Metadata.quoteIdentifier(field.name());
        assignments.add(quoted + " = excluded." + quoted);
      }
      sql.append("DO UPDATE SET ").append(assignments);
    }
    return new D1Statement(sql.toString(), parameters);
  }

  D1Statement delete(D1CollectionMetadata metadata, DocumentKey key) {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(key, "key");
    requireCollection(metadata, key.collection());
    return new D1Statement(
        "DELETE FROM "
            + D1Metadata.quoteIdentifier(metadata.collection())
            + " WHERE "
            + D1Metadata.quoteIdentifier(D1Metadata.DOCUMENT_ID)
            + " = ?1",
        List.of(new D1Parameter.TextParameter(key.id())));
  }

  CompiledQuery query(D1CollectionMetadata metadata, IndexQuery query) {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(query, "query");
    requireCollection(metadata, query.collection());

    List<D1Parameter> parameters = new ArrayList<>();
    List<String> conditions = new ArrayList<>();
    for (IndexQuery.Filter filter : query.filters()) {
      D1IndexedField field = metadata.requireIndexedField(filter.indexedField());
      requireOperator(field, filter.operator());
      D1Parameter parameter = field.type().parameter(field.name(), filter.value());
      String placeholder = addParameter(parameters, parameter);
      conditions.add(
          D1Metadata.quoteIdentifier(field.name())
              + " "
              + operatorSql(filter.operator())
              + " "
              + placeholder);
    }

    D1IndexedField sortField =
        query.sort().map(sort -> metadata.requireIndexedField(sort.indexedField())).orElse(null);
    query
        .cursor()
        .ifPresent(
            cursor ->
                conditions.add(cursorCondition(metadata, query, sortField, cursor, parameters)));

    StringBuilder sql = new StringBuilder("SELECT ");
    sql.append(D1Metadata.quoteIdentifier(D1Metadata.DOCUMENT_ID));
    if (sortField != null) {
      sql.append(", ").append(D1Metadata.quoteIdentifier(sortField.name()));
    }
    sql.append(" FROM ").append(D1Metadata.quoteIdentifier(metadata.collection()));
    if (!conditions.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", conditions));
    }

    SortDirection direction =
        query.sort().map(IndexQuery.Sort::direction).orElse(SortDirection.ASC);
    if (sortField != null) {
      sql.append(" ORDER BY ")
          .append(D1Metadata.quoteIdentifier(sortField.name()))
          .append(' ')
          .append(direction)
          .append(", ")
          .append(D1Metadata.quoteIdentifier(D1Metadata.DOCUMENT_ID))
          .append(' ')
          .append(direction);
    } else {
      sql.append(" ORDER BY ")
          .append(D1Metadata.quoteIdentifier(D1Metadata.DOCUMENT_ID))
          .append(" ASC");
    }
    String limitPlaceholder =
        addParameter(parameters, new D1Parameter.IntegerParameter((long) query.limit() + 1L));
    sql.append(" LIMIT ").append(limitPlaceholder);
    return new CompiledQuery(new D1Statement(sql.toString(), parameters), query.limit(), sortField);
  }

  IndexPage page(D1CollectionMetadata metadata, CompiledQuery query, D1Result result) {
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(result, "result");
    int returnedCount = Math.min(result.rows().size(), query.requestedLimit());
    List<DocumentKey> keys = new ArrayList<>(returnedCount);
    List<IndexValue> sortValues = new ArrayList<>(returnedCount);
    D1IndexedField sortField = query.sortField();
    for (int index = 0; index < returnedCount; index++) {
      Map<String, @Nullable Object> row = result.rows().get(index);
      keys.add(readDocumentKey(metadata, row));
      if (sortField != null) {
        sortValues.add(
            sortField.type().readIndexValue(sortField.name(), row.get(sortField.name())));
      }
    }
    Optional<IndexCursor> nextCursor = Optional.empty();
    if (result.rows().size() > query.requestedLimit() && returnedCount > 0) {
      Optional<IndexValue> sortValue =
          sortField != null ? Optional.of(sortValues.get(returnedCount - 1)) : Optional.empty();
      nextCursor = Optional.of(new IndexCursor(keys.get(returnedCount - 1), sortValue));
    }
    return new IndexPage(keys, nextCursor);
  }

  private static String cursorCondition(
      D1CollectionMetadata metadata,
      IndexQuery query,
      @Nullable D1IndexedField sortField,
      IndexCursor cursor,
      List<D1Parameter> parameters) {
    requireCollection(metadata, cursor.lastDocumentKey().collection());
    String documentId = D1Metadata.quoteIdentifier(D1Metadata.DOCUMENT_ID);
    if (sortField == null) {
      if (cursor.sortValue().isPresent()) {
        throw new IllegalArgumentException("unsorted query cursor must not include a sort value");
      }
      String idParameter =
          addParameter(parameters, new D1Parameter.TextParameter(cursor.lastDocumentKey().id()));
      return documentId + " > " + idParameter;
    }

    IndexValue sortValue =
        cursor
            .sortValue()
            .orElseThrow(
                () ->
                    new IllegalArgumentException("sorted query cursor must include a sort value"));
    String sortParameter =
        addParameter(parameters, sortField.type().parameter(sortField.name(), sortValue));
    String idParameter =
        addParameter(parameters, new D1Parameter.TextParameter(cursor.lastDocumentKey().id()));
    String comparison = query.sort().orElseThrow().direction() == SortDirection.ASC ? ">" : "<";
    String column = D1Metadata.quoteIdentifier(sortField.name());
    return "("
        + column
        + " "
        + comparison
        + " "
        + sortParameter
        + " OR ("
        + column
        + " = "
        + sortParameter
        + " AND "
        + documentId
        + " "
        + comparison
        + " "
        + idParameter
        + "))";
  }

  private static void validateEntry(D1CollectionMetadata metadata, Map<String, IndexValue> values) {
    for (String supplied : values.keySet()) {
      metadata.requireIndexedField(supplied);
    }
    for (D1IndexedField field : metadata.indexedFields()) {
      IndexValue value = values.get(field.name());
      if (value == null) {
        throw new IllegalArgumentException(
            "indexed field value must not be null or missing: " + field.name());
      }
      field.type().requireCompatible(field.name(), value);
    }
  }

  private static void requireOperator(D1IndexedField field, ComparisonOperator operator) {
    if (field.type() == D1ValueType.BOOLEAN
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

  private static String addParameter(List<D1Parameter> parameters, D1Parameter parameter) {
    parameters.add(parameter);
    return "?" + parameters.size();
  }

  private static void requireCollection(D1CollectionMetadata metadata, String actualCollection) {
    if (!metadata.collection().equals(actualCollection)) {
      throw new IllegalArgumentException(
          "collection does not match initialized D1 metadata: " + actualCollection);
    }
  }

  private static DocumentKey readDocumentKey(
      D1CollectionMetadata metadata, Map<String, @Nullable Object> row) {
    Object value = row.get(D1Metadata.DOCUMENT_ID);
    if (!(value instanceof String id)) {
      throw new StorageException.Operation("D1 query returned an invalid document_id");
    }
    try {
      return new DocumentKey(metadata.collection(), id);
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw new StorageException.Operation("D1 query returned an invalid document_id", failure);
    }
  }

  /** Validated context shared by query SQL compilation and page mapping. */
  record CompiledQuery(
      D1Statement statement, int requestedLimit, @Nullable D1IndexedField sortField) {

    CompiledQuery {
      Objects.requireNonNull(statement, "statement");
      if (requestedLimit <= 0) {
        throw new IllegalArgumentException("requestedLimit must be greater than zero");
      }
    }
  }
}
