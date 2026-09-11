package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** SQLite-specific storage, schema, locking, and configuration coverage. */
class SqliteIndexStoreTest extends JdbcBackendTest {

  private static final Duration SQLITE_BUSY_FAILURE_TIMEOUT = Duration.ofSeconds(10);

  @Override
  protected String fileUrl(Path databasePath) {
    return "jdbc:sqlite:" + sqlitePath(databasePath);
  }

  @Override
  protected String stringColumnType() {
    return "TEXT";
  }

  @Override
  protected String longColumnType() {
    return "INTEGER";
  }

  @Override
  protected void assertDatabaseFiles(Path databasePath) {
    assertThat(Files.isRegularFile(sqlitePath(databasePath))).isTrue();
  }

  @Test
  void storesBooleansAsIntegersAndRejectsInvalidBooleanResults() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("booleans");
    try (JdbcExecution execution = JdbcExecution.create(2, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(BooleanDocument.class));
      completedValue(
          store.upsert(
              entry(
                  "boolean_entries",
                  "true-value",
                  Map.of("active", new IndexValue.BooleanValue(true)))));
      completedValue(
          store.upsert(
              entry(
                  "boolean_entries",
                  "false-value",
                  Map.of("active", new IndexValue.BooleanValue(false)))));

      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement();
          ResultSet resultSet =
              statement.executeQuery(
                  "SELECT \"document_id\", \"active\", typeof(\"active\") "
                      + "FROM \"boolean_entries\" ORDER BY \"document_id\"")) {
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getString(1)).isEqualTo("false-value");
        assertThat(resultSet.getInt(2)).isZero();
        assertThat(resultSet.getString(3)).isEqualTo("integer");
        assertThat(resultSet.next()).isTrue();
        assertThat(resultSet.getString(1)).isEqualTo("true-value");
        assertThat(resultSet.getInt(2)).isEqualTo(1);
        assertThat(resultSet.getString(3)).isEqualTo("integer");
        assertThat(resultSet.next()).isFalse();
      }

      execute(dataSource, "UPDATE \"boolean_entries\" SET \"active\" = 2");
      IndexQuery sortedBooleanQuery =
          new IndexQuery(
              "boolean_entries",
              List.of(),
              Optional.of(new IndexQuery.Sort("active", SortDirection.ASC)),
              10,
              Optional.empty());
      assertThat(completedFailure(store.query(sortedBooleanQuery)))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessageContaining("invalid value for indexed field 'active'");
    }
  }

  @Test
  void supportsZeroIndexUpsertWithoutReplaceSemantics() {
    TestDriverManagerDataSource dataSource = dataSource("zero_index");
    DocumentKey key = new DocumentKey("zero_index_entries", "one");
    try (JdbcExecution execution = JdbcExecution.create(2, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(ZeroIndexDocument.class));
      completedValue(store.upsert(new IndexEntry(key, Map.of())));
      completedValue(store.upsert(new IndexEntry(key, Map.of())));
      assertThat(completedValue(store.query(unsortedQuery("zero_index_entries"))).documentKeys())
          .containsExactly(key);
    }
  }

  @Test
  void appliesBusyTimeoutAndTranslatesLockedWritesAsUnavailable() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("locked");
    try (JdbcExecution execution = JdbcExecution.create(2, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(LockDocument.class));
      completedValue(
          store.upsert(
              entry("lock_entries", "locked", Map.of("rank", new IndexValue.LongValue(1L)))));

      try (Connection blocker = dataSource.getConnection();
          PreparedStatement statement =
              blocker.prepareStatement(
                  "UPDATE \"lock_entries\" SET \"rank\" = ? WHERE \"document_id\" = ?")) {
        blocker.setAutoCommit(false);
        statement.setLong(1, 2L);
        statement.setString(2, "locked");
        assertThat(statement.executeUpdate()).isEqualTo(1);

        long startedAt = System.nanoTime();
        Throwable failure =
            completedFailure(
                store.upsert(
                    entry("lock_entries", "locked", Map.of("rank", new IndexValue.LongValue(3L)))),
                SQLITE_BUSY_FAILURE_TIMEOUT);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertThat(failure)
            .isInstanceOf(StorageException.Unavailable.class)
            .hasMessage("JDBC upsert failed")
            .hasCauseInstanceOf(SQLException.class);
        assertThat(((SQLException) failure.getCause()).getErrorCode() & 0xff).isEqualTo(5);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(4_500));
        blocker.rollback();
      }
    }
  }

  @Test
  void preservesCallerSelectedJournalModes() throws SQLException {
    assertJournalModePreserved("delete");
    assertJournalModePreserved("wal");
  }

  @Test
  void allowsReadsWhileAnotherConnectionHasAnActiveWriteTransaction() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("reader_writer");
    try (JdbcExecution execution = JdbcExecution.create(3, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(LockDocument.class));
      completedValue(
          store.upsert(
              entry("lock_entries", "visible", Map.of("rank", new IndexValue.LongValue(1L)))));

      try (Connection blocker = dataSource.getConnection();
          PreparedStatement statement =
              blocker.prepareStatement(
                  "UPDATE \"lock_entries\" SET \"rank\" = ? WHERE \"document_id\" = ?")) {
        blocker.setAutoCommit(false);
        statement.setLong(1, 2L);
        statement.setString(2, "visible");
        assertThat(statement.executeUpdate()).isEqualTo(1);

        List<CompletionStage<?>> reads =
            List.of(
                store.query(unsortedQuery("lock_entries")),
                store.query(unsortedQuery("lock_entries")));
        CompletableFuture<?>[] futures =
            reads.stream()
                .map(stage -> stage.toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        completedValue(CompletableFuture.allOf(futures));
        reads.forEach(
            read ->
                assertThat(((IndexPage) completedValue(read)).documentKeys())
                    .containsExactly(new DocumentKey("lock_entries", "visible")));
        blocker.rollback();
      }
    }
  }

  @Test
  void rejectsInMemoryDatabases() {
    TestDriverManagerDataSource dataSource =
        new TestDriverManagerDataSource("jdbc:sqlite::memory:");
    try (JdbcExecution execution = JdbcExecution.create(1, 4)) {
      Throwable failure =
          completedFailure(
              new JdbcIndexStore(dataSource, execution).initialize(JournalDocument.class));
      assertThat(failure)
          .isInstanceOf(StorageException.Operation.class)
          .hasMessage("SQLite requires a persistent local file-backed main database");
    }
  }

  @Test
  void rejectsUniqueAndPartialRequiredIndexes() throws SQLException {
    assertIncompatibleIndex("unique", "CREATE UNIQUE INDEX", "");
    assertIncompatibleIndex("partial", "CREATE INDEX", " WHERE \"rank\" > 0");
  }

  @Test
  void createsExactPhysicalSchemaAndPreservesIndexesAfterClear() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("physical_schema");
    try (JdbcExecution execution = JdbcExecution.create(2, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(PhysicalDocument.class));

      assertThat(sqliteColumns(dataSource, "physical_entries"))
          .containsEntry("document_id", new SqliteColumn("TEXT", true, 1))
          .containsEntry("country", new SqliteColumn("TEXT", true, 0))
          .containsEntry("rank", new SqliteColumn("INTEGER", true, 0))
          .containsEntry("score", new SqliteColumn("REAL", true, 0))
          .containsEntry("active", new SqliteColumn("INTEGER", true, 0));
      Map<String, List<String>> expectedIndexes =
          Map.of(
              "idx_physical_entries_active", List.of("active"),
              "idx_physical_entries_country", List.of("country"),
              "idx_physical_entries_rank", List.of("rank"),
              "idx_physical_entries_score", List.of("score"));
      assertThat(sqliteIndexes(dataSource, "physical_entries"))
          .containsAllEntriesOf(expectedIndexes);

      completedValue(
          store.upsert(
              entry(
                  "physical_entries",
                  "one",
                  Map.of(
                      "active", new IndexValue.BooleanValue(true),
                      "country", new IndexValue.StringValue("NZ"),
                      "rank", new IndexValue.LongValue(1L),
                      "score", new IndexValue.DoubleValue(1.5)))));
      completedValue(store.clear("physical_entries"));

      assertThat(rowCount(dataSource, "physical_entries")).isZero();
      assertThat(sqliteIndexes(dataSource, "physical_entries"))
          .containsAllEntriesOf(expectedIndexes);
    }
  }

  @Test
  void completesConcurrentWritesWithinBoundedExecution() {
    TestDriverManagerDataSource dataSource = dataSource("concurrent_writes");
    dataSource.trackConnections();
    try (JdbcExecution execution = JdbcExecution.create(4, 64)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(LockDocument.class));

      List<CompletionStage<@org.jspecify.annotations.Nullable Void>> writes = new ArrayList<>();
      for (int index = 0; index < 40; index++) {
        writes.add(
            store.upsert(
                entry(
                    "lock_entries",
                    "document-" + index,
                    Map.of("rank", new IndexValue.LongValue(index)))));
      }
      CompletableFuture<?>[] futures =
          writes.stream()
              .map(stage -> stage.toCompletableFuture())
              .toArray(CompletableFuture[]::new);
      completedValue(CompletableFuture.allOf(futures));

      IndexQuery allRows =
          new IndexQuery("lock_entries", List.of(), Optional.empty(), 100, Optional.empty());
      assertThat(completedValue(store.query(allRows)).documentKeys()).hasSize(40);

      List<CompletionStage<?>> reads = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        reads.add(store.query(allRows));
      }
      CompletableFuture<?>[] readFutures =
          reads.stream()
              .map(stage -> stage.toCompletableFuture())
              .toArray(CompletableFuture[]::new);
      completedValue(CompletableFuture.allOf(readFutures));
      assertThat(dataSource.peakConnectionCount()).isLessThanOrEqualTo(4);
    }
  }

  private void assertJournalModePreserved(String mode) throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("journal_" + mode);
    setJournalMode(dataSource, mode);
    try (JdbcExecution execution = JdbcExecution.create(2, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(JournalDocument.class));
      assertThat(journalMode(dataSource)).isEqualTo(mode);
    }
  }

  private void assertIncompatibleIndex(String name, String createIndex, String suffix)
      throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("index_" + name);
    execute(
        dataSource,
        "CREATE TABLE \"index_entries\" ("
            + "\"document_id\" TEXT NOT NULL PRIMARY KEY, \"rank\" INTEGER NOT NULL)");
    execute(
        dataSource,
        createIndex + " \"idx_index_entries_rank\" ON \"index_entries\" (\"rank\")" + suffix);
    try (JdbcExecution execution = JdbcExecution.create(1, 4)) {
      assertIncompatible(
          new JdbcIndexStore(dataSource, execution).initialize(IndexDocument.class),
          "index for rank");
    }
  }

  private static void setJournalMode(TestDriverManagerDataSource dataSource, String mode)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode = " + mode)) {
      assertThat(resultSet.next()).isTrue();
      assertThat(resultSet.getString(1)).isEqualTo(mode);
    }
  }

  private static String journalMode(TestDriverManagerDataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode")) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getString(1);
    }
  }

  private static Map<String, SqliteColumn> sqliteColumns(
      TestDriverManagerDataSource dataSource, String table) throws SQLException {
    Map<String, SqliteColumn> columns = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery("PRAGMA main.table_xinfo(\"" + table + "\")")) {
      while (resultSet.next()) {
        columns.put(
            resultSet.getString("name"),
            new SqliteColumn(
                resultSet.getString("type"),
                resultSet.getInt("notnull") == 1,
                resultSet.getInt("pk")));
      }
    }
    return Map.copyOf(columns);
  }

  private static Map<String, List<String>> sqliteIndexes(
      TestDriverManagerDataSource dataSource, String table) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("PRAGMA main.index_list(\"" + table + "\")")) {
      while (resultSet.next()) {
        String name = resultSet.getString("name");
        if (name.startsWith("idx_")) {
          names.add(name);
        }
      }
    }
    Map<String, List<String>> indexes = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      for (String name : names) {
        List<String> columns = new ArrayList<>();
        try (ResultSet resultSet =
            statement.executeQuery("PRAGMA main.index_info(\"" + name + "\")")) {
          while (resultSet.next()) {
            columns.add(resultSet.getString("name"));
          }
        }
        indexes.put(name, List.copyOf(columns));
      }
    }
    return Map.copyOf(indexes);
  }

  private static Path sqlitePath(Path path) {
    return path.resolveSibling(path.getFileName() + ".db").toAbsolutePath();
  }

  private record SqliteColumn(String type, boolean notNull, int primaryKeyOrder) {}

  @Document("boolean_entries")
  private static final class BooleanDocument {
    @Index private boolean active;
  }

  @Document("zero_index_entries")
  private static final class ZeroIndexDocument {}

  @Document("lock_entries")
  private static final class LockDocument {
    @Index private long rank;
  }

  @Document("journal_entries")
  private static final class JournalDocument {
    @Index private String country;
  }

  @Document("index_entries")
  private static final class IndexDocument {
    @Index private long rank;
  }

  @Document("physical_entries")
  private static final class PhysicalDocument {
    @Index private String country;
    @Index private long rank;
    @Index private double score;
    @Index private boolean active;
  }
}
