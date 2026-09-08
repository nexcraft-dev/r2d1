package dev.nexcraft.r2d1.d1;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.spi.StorageException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class D1SchemaManagerTest {

  private static final D1CollectionMetadata METADATA =
      new D1CollectionMetadata(
          D1SchemaManagerTest.class,
          "users",
          List.of(new D1IndexedField("country", D1ValueType.STRING)));

  @Test
  void createsMissingTableAndPhysicalIndexes() {
    ScriptedD1Transport transport = new ScriptedD1Transport();
    transport.expect("PRAGMA table_info(\"users\")", List.of());
    transport.expect(
        "CREATE TABLE IF NOT EXISTS \"users\" (\"document_id\" TEXT PRIMARY KEY NOT NULL, "
            + "\"country\" TEXT NOT NULL)");
    transport.expect(
        "PRAGMA table_info(\"users\")",
        List.of(column("document_id", "TEXT", 1, 1), column("country", "TEXT", 1, 0)));
    expectIndexCreation(transport);

    new D1SchemaManager(transport).initialize(METADATA).toCompletableFuture().join();

    transport.assertExhausted();
  }

  @Test
  void acceptsCompatibleSchemaAndLeavesRemovedFieldsAndIndexesUntouched() {
    ScriptedD1Transport transport = new ScriptedD1Transport();
    transport.expect(
        "PRAGMA table_info(\"users\")",
        List.of(
            column("document_id", "TEXT", 1, 1),
            column("country", "TEXT", 1, 0),
            column("oldField", "INTEGER", 1, 0)));
    List<Map<String, Object>> indexes =
        List.of(index("idx_users_country"), index("idx_users_oldField"));
    transport.expect("PRAGMA index_list(\"users\")", indexes);
    transport.expect("PRAGMA index_list(\"users\")", indexes);
    transport.expect("PRAGMA index_info(\"idx_users_country\")", List.of(indexedColumn("country")));

    new D1SchemaManager(transport).initialize(METADATA).toCompletableFuture().join();

    assertThat(transport.statements())
        .noneMatch(
            statement ->
                statement.sql().startsWith("DROP") || statement.sql().contains("oldField\")"));
    transport.assertExhausted();
  }

  @Test
  void addsARequiredColumnOnlyWhenTheExistingTableIsEmpty() {
    ScriptedD1Transport transport = new ScriptedD1Transport();
    transport.expect("PRAGMA table_info(\"users\")", List.of(column("document_id", "TEXT", 1, 1)));
    transport.expect(
        "SELECT EXISTS(SELECT 1 FROM \"users\" LIMIT 1) AS \"has_rows\"",
        List.of(Map.of("has_rows", 0L)));
    transport.expect("ALTER TABLE \"users\" ADD COLUMN \"country\" TEXT NOT NULL DEFAULT ''");
    transport.expect(
        "PRAGMA table_info(\"users\")",
        List.of(column("document_id", "TEXT", 1, 1), column("country", "TEXT", 1, 0)));
    expectIndexCreation(transport);

    new D1SchemaManager(transport).initialize(METADATA).toCompletableFuture().join();

    transport.assertExhausted();
  }

  @Test
  void failsBeforeAddingARequiredColumnToAPopulatedTable() {
    ScriptedD1Transport transport = new ScriptedD1Transport();
    transport.expect("PRAGMA table_info(\"users\")", List.of(column("document_id", "TEXT", 1, 1)));
    transport.expect(
        "SELECT EXISTS(SELECT 1 FROM \"users\" LIMIT 1) AS \"has_rows\"",
        List.of(Map.of("has_rows", 1L)));

    Throwable failure = completedFailure(new D1SchemaManager(transport).initialize(METADATA));

    assertThat(failure)
        .isInstanceOf(StorageException.Operation.class)
        .hasMessageContaining("requires a rebuild");
    assertThat(transport.statements()).noneMatch(statement -> statement.sql().startsWith("ALTER"));
    transport.assertExhausted();
  }

  @Test
  void rejectsIncompatibleColumnTypesWithoutMigration() {
    ScriptedD1Transport transport = new ScriptedD1Transport();
    transport.expect(
        "PRAGMA table_info(\"users\")",
        List.of(column("document_id", "TEXT", 1, 1), column("country", "INTEGER", 1, 0)));

    Throwable failure = completedFailure(new D1SchemaManager(transport).initialize(METADATA));

    assertThat(failure)
        .isInstanceOf(StorageException.Operation.class)
        .hasMessageContaining("incompatible")
        .hasMessageContaining("country");
    transport.assertExhausted();
  }

  @Test
  void rejectsAnExpectedIndexThatTargetsAnotherColumn() {
    ScriptedD1Transport transport = new ScriptedD1Transport();
    transport.expect(
        "PRAGMA table_info(\"users\")",
        List.of(column("document_id", "TEXT", 1, 1), column("country", "TEXT", 1, 0)));
    List<Map<String, Object>> indexes = List.of(index("idx_users_country"));
    transport.expect("PRAGMA index_list(\"users\")", indexes);
    transport.expect("PRAGMA index_list(\"users\")", indexes);
    transport.expect(
        "PRAGMA index_info(\"idx_users_country\")", List.of(indexedColumn("oldField")));

    Throwable failure = completedFailure(new D1SchemaManager(transport).initialize(METADATA));

    assertThat(failure)
        .isInstanceOf(StorageException.Operation.class)
        .hasMessageContaining("index is incompatible");
    transport.assertExhausted();
  }

  private static void expectIndexCreation(ScriptedD1Transport transport) {
    transport.expect("PRAGMA index_list(\"users\")", List.of());
    transport.expect("CREATE INDEX IF NOT EXISTS \"idx_users_country\" ON \"users\" (\"country\")");
    transport.expect("PRAGMA index_list(\"users\")", List.of(index("idx_users_country")));
    transport.expect("PRAGMA index_info(\"idx_users_country\")", List.of(indexedColumn("country")));
  }

  private static Map<String, Object> column(
      String name, String type, long notNull, long primaryKey) {
    return Map.of("name", name, "type", type, "notnull", notNull, "pk", primaryKey);
  }

  private static Map<String, Object> index(String name) {
    return Map.of("name", name);
  }

  private static Map<String, Object> indexedColumn(String name) {
    return Map.of("name", name);
  }

  private static Throwable completedFailure(java.util.concurrent.CompletionStage<?> stage) {
    try {
      stage.toCompletableFuture().join();
      throw new AssertionError("stage completed successfully");
    } catch (CompletionException failure) {
      return failure.getCause();
    }
  }
}
