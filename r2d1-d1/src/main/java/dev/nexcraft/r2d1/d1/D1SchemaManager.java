package dev.nexcraft.r2d1.d1;

import dev.nexcraft.r2d1.spi.StorageException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/** Reconciles tables and single-column indexes against the actual D1/SQLite schema. */
final class D1SchemaManager {

  private final D1Transport transport;

  D1SchemaManager(D1Transport transport) {
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  CompletionStage<@Nullable Void> initialize(D1CollectionMetadata metadata) {
    Objects.requireNonNull(metadata, "metadata");
    return inspectColumns(metadata)
        .thenCompose(
            columns ->
                columns.isEmpty()
                    ? createTable(metadata).thenCompose(ignored -> validateFinalTable(metadata))
                    : reconcileTable(metadata, columns))
        .thenCompose(ignored -> ensureIndexes(metadata));
  }

  private CompletionStage<@Nullable Void> createTable(D1CollectionMetadata metadata) {
    List<String> definitions = new ArrayList<>();
    definitions.add(
        D1Metadata.quoteIdentifier(D1Metadata.DOCUMENT_ID) + " TEXT PRIMARY KEY NOT NULL");
    for (D1IndexedField field : metadata.indexedFields()) {
      definitions.add(
          D1Metadata.quoteIdentifier(field.name()) + " " + field.type().sqlType() + " NOT NULL");
    }
    return execute(
            D1Statement.of(
                "CREATE TABLE IF NOT EXISTS "
                    + D1Metadata.quoteIdentifier(metadata.collection())
                    + " ("
                    + String.join(", ", definitions)
                    + ")"))
        .thenAccept(ignored -> {});
  }

  private CompletionStage<@Nullable Void> reconcileTable(
      D1CollectionMetadata metadata, Map<String, ColumnInfo> columns) {
    validateCompatibleColumns(metadata, columns, false);
    List<D1IndexedField> missing =
        metadata.indexedFields().stream()
            .filter(field -> !columns.containsKey(field.name()))
            .toList();
    if (missing.isEmpty()) {
      return CompletableFuture.<@Nullable Void>completedFuture(null);
    }
    return hasRows(metadata)
        .thenCompose(
            hasRows -> {
              if (hasRows) {
                return CompletableFuture.failedFuture(
                    new StorageException.Operation(
                        "D1 schema requires a rebuild before adding required indexed fields to "
                            + metadata.collection()));
              }
              return addColumns(metadata, missing)
                  .thenCompose(ignored -> validateFinalTable(metadata));
            });
  }

  private CompletionStage<@Nullable Void> addColumns(
      D1CollectionMetadata metadata, List<D1IndexedField> missing) {
    CompletionStage<@Nullable Void> stage = CompletableFuture.<@Nullable Void>completedFuture(null);
    for (D1IndexedField field : missing) {
      stage =
          stage.thenCompose(
              ignored ->
                  execute(
                          D1Statement.of(
                              "ALTER TABLE "
                                  + D1Metadata.quoteIdentifier(metadata.collection())
                                  + " ADD COLUMN "
                                  + D1Metadata.quoteIdentifier(field.name())
                                  + " "
                                  + field.type().sqlType()
                                  + " NOT NULL DEFAULT "
                                  + field.type().additiveDefault()))
                      .thenAccept(result -> {}));
    }
    return stage;
  }

  private CompletionStage<@Nullable Void> validateFinalTable(D1CollectionMetadata metadata) {
    return inspectColumns(metadata)
        .thenAccept(
            columns -> {
              validateCompatibleColumns(metadata, columns, true);
            });
  }

  private static void validateCompatibleColumns(
      D1CollectionMetadata metadata, Map<String, ColumnInfo> columns, boolean requireAll) {
    ColumnInfo documentId = columns.get(D1Metadata.DOCUMENT_ID);
    if (documentId == null
        || !"TEXT".equals(documentId.type())
        || !documentId.notNull()
        || documentId.primaryKeyPosition() <= 0) {
      throw incompatible(metadata, D1Metadata.DOCUMENT_ID);
    }
    for (D1IndexedField field : metadata.indexedFields()) {
      ColumnInfo actual = columns.get(field.name());
      if (actual == null) {
        if (requireAll) {
          throw incompatible(metadata, field.name());
        }
        continue;
      }
      if (!field.type().sqlType().equals(actual.type())
          || !actual.notNull()
          || actual.primaryKeyPosition() != 0) {
        throw incompatible(metadata, field.name());
      }
    }
  }

  private CompletionStage<Map<String, ColumnInfo>> inspectColumns(D1CollectionMetadata metadata) {
    return execute(
            D1Statement.of(
                "PRAGMA table_info(" + D1Metadata.quoteIdentifier(metadata.collection()) + ")"))
        .thenApply(
            result -> {
              Map<String, ColumnInfo> columns = new LinkedHashMap<>();
              for (Map<String, @Nullable Object> row : result.rows()) {
                String name = requireString(row, "name", "table column name");
                String type =
                    requireString(row, "type", "table column type").toUpperCase(Locale.ROOT);
                boolean notNull = requireLong(row, "notnull", "table column notnull") == 1L;
                long primaryKeyValue = requireLong(row, "pk", "table column pk");
                if (primaryKeyValue < 0 || primaryKeyValue > Integer.MAX_VALUE) {
                  throw new StorageException.Operation(
                      "D1 schema inspection returned an invalid table column pk");
                }
                int primaryKey = (int) primaryKeyValue;
                ColumnInfo previous = columns.put(name, new ColumnInfo(type, notNull, primaryKey));
                if (previous != null) {
                  throw new StorageException.Operation(
                      "D1 schema inspection returned a duplicate column: " + name);
                }
              }
              return Map.copyOf(columns);
            });
  }

  private CompletionStage<Boolean> hasRows(D1CollectionMetadata metadata) {
    return execute(
            D1Statement.of(
                "SELECT EXISTS(SELECT 1 FROM "
                    + D1Metadata.quoteIdentifier(metadata.collection())
                    + " LIMIT 1) AS "
                    + D1Metadata.quoteIdentifier("has_rows")))
        .thenApply(
            result -> {
              if (result.rows().size() != 1) {
                throw new StorageException.Operation(
                    "D1 row-existence query returned an invalid result");
              }
              long value = requireLong(result.rows().getFirst(), "has_rows", "row existence");
              if (value != 0L && value != 1L) {
                throw new StorageException.Operation(
                    "D1 row-existence query returned an invalid value");
              }
              return value == 1L;
            });
  }

  private CompletionStage<@Nullable Void> ensureIndexes(D1CollectionMetadata metadata) {
    CompletionStage<@Nullable Void> stage = CompletableFuture.<@Nullable Void>completedFuture(null);
    for (D1IndexedField field : metadata.indexedFields()) {
      stage = stage.thenCompose(ignored -> ensureIndex(metadata, field));
    }
    return stage;
  }

  private CompletionStage<@Nullable Void> ensureIndex(
      D1CollectionMetadata metadata, D1IndexedField field) {
    String indexName = D1Metadata.physicalIndexName(metadata.collection(), field.name());
    return inspectIndexNames(metadata)
        .thenCompose(
            names -> {
              if (names.contains(indexName)) {
                return CompletableFuture.<@Nullable Void>completedFuture(null);
              }
              return execute(
                      D1Statement.of(
                          "CREATE INDEX IF NOT EXISTS "
                              + D1Metadata.quoteIdentifier(indexName)
                              + " ON "
                              + D1Metadata.quoteIdentifier(metadata.collection())
                              + " ("
                              + D1Metadata.quoteIdentifier(field.name())
                              + ")"))
                  .thenAccept(ignored -> {});
            })
        .thenCompose(ignored -> inspectIndexNames(metadata))
        .thenCompose(
            names -> {
              if (!names.contains(indexName)) {
                return CompletableFuture.failedFuture(
                    new StorageException.Operation(
                        "D1 index name is unavailable for collection " + metadata.collection()));
              }
              return validateIndex(indexName, field);
            });
  }

  private CompletionStage<Set<String>> inspectIndexNames(D1CollectionMetadata metadata) {
    return execute(
            D1Statement.of(
                "PRAGMA index_list(" + D1Metadata.quoteIdentifier(metadata.collection()) + ")"))
        .thenApply(
            result -> {
              Set<String> names = new LinkedHashSet<>();
              for (Map<String, @Nullable Object> row : result.rows()) {
                names.add(requireString(row, "name", "index name"));
              }
              return Set.copyOf(names);
            });
  }

  private CompletionStage<@Nullable Void> validateIndex(String indexName, D1IndexedField field) {
    return execute(
            D1Statement.of("PRAGMA index_info(" + D1Metadata.quoteIdentifier(indexName) + ")"))
        .thenAccept(
            result -> {
              if (result.rows().size() != 1
                  || !field
                      .name()
                      .equals(
                          requireString(result.rows().getFirst(), "name", "indexed column name"))) {
                throw new StorageException.Operation(
                    "D1 index is incompatible with indexed field " + field.name());
              }
            });
  }

  private CompletionStage<D1Result> execute(D1Statement statement) {
    try {
      return Objects.requireNonNull(
          transport.execute(statement), "D1 transport returned a null stage");
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(
          failure instanceof StorageException
              ? failure
              : new StorageException.Operation("D1 schema operation failed", failure));
    }
  }

  private static String requireString(
      Map<String, @Nullable Object> row, String field, String description) {
    Object value = row.get(field);
    if (value instanceof String text) {
      return text;
    }
    throw new StorageException.Operation("D1 schema inspection returned an invalid " + description);
  }

  private static long requireLong(
      Map<String, @Nullable Object> row, String field, String description) {
    Object value = row.get(field);
    if (value instanceof Number number) {
      double decimal = number.doubleValue();
      long integer = number.longValue();
      if (Double.isFinite(decimal) && decimal == integer) {
        return integer;
      }
    }
    throw new StorageException.Operation("D1 schema inspection returned an invalid " + description);
  }

  private static StorageException.Operation incompatible(
      D1CollectionMetadata metadata, String field) {
    return new StorageException.Operation(
        "D1 schema is incompatible for collection "
            + metadata.collection()
            + " at column "
            + field);
  }

  private record ColumnInfo(String type, boolean notNull, int primaryKeyPosition) {}
}
