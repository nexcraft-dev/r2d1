package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Shared schema, persistence, and parameter coverage for built-in JDBC backends. */
abstract class JdbcBackendTest {

  @TempDir protected Path temporaryDirectory;

  protected abstract String fileUrl(Path databasePath);

  protected abstract String stringColumnType();

  protected abstract String longColumnType();

  protected void assertDatabaseFiles(Path databasePath) {}

  protected String reopenUrl(String url) {
    return url;
  }

  protected void shutdown(DataSource dataSource) throws SQLException {}

  /** Creates the execution resource used by the backend lifecycle fixture. */
  protected JdbcExecution createExecution(int maxConcurrency, int maxPending) {
    return JdbcExecution.create(maxConcurrency, maxPending);
  }

  /** Creates a Java 25 virtual-thread execution resource for backend mode parity tests. */
  protected final JdbcExecution createVirtualExecution(int maxConcurrency, int maxPending) {
    return JdbcExecution.create(
        new JdbcExecutionConfig(JdbcExecutionMode.VIRTUAL_THREAD, maxConcurrency, maxPending));
  }

  @Test
  void persistsRowsAcrossDataSourceAndExecutionReopen() throws SQLException {
    Path databasePath = temporaryDirectory.resolve("persistent");
    String url = fileUrl(databasePath);
    TestDriverManagerDataSource firstDataSource = new TestDriverManagerDataSource(url);
    firstDataSource.trackResources();

    try (JdbcExecution execution = createExecution(2, 16)) {
      JdbcIndexStore store = new JdbcIndexStore(firstDataSource, execution);
      completedValue(store.initialize(PersistentDocument.class));
      completedValue(store.upsert(persistentEntry("a", "NZ", 10L, 1.5, true)));
      completedValue(store.upsert(persistentEntry("b", "NZ", 20L, 2.5, false)));
      completedValue(store.upsert(persistentEntry("c", "AU", 30L, 3.5, true)));
      assertNoOpenResources(firstDataSource);
    }
    shutdown(firstDataSource);
    assertNoOpenResources(firstDataSource);
    assertDatabaseFiles(databasePath);

    TestDriverManagerDataSource reopenedDataSource =
        new TestDriverManagerDataSource(reopenUrl(url));
    reopenedDataSource.trackResources();
    reopenedDataSource.resetDdlStatementCount();
    try (JdbcExecution execution = createExecution(2, 16)) {
      JdbcIndexStore reopened = new JdbcIndexStore(reopenedDataSource, execution);
      completedValue(reopened.initialize(PersistentDocument.class));
      assertThat(reopenedDataSource.resourceSnapshot().ddlStatements()).isZero();
      IndexPage page =
          completedValue(
              reopened.query(
                  new IndexQuery(
                      "persistent_entries",
                      List.of(
                          new IndexQuery.Filter(
                              "country",
                              ComparisonOperator.EQUAL,
                              new IndexValue.StringValue("NZ"))),
                      Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
                      10,
                      Optional.empty())));
      assertThat(page.documentKeys())
          .containsExactly(
              new DocumentKey("persistent_entries", "a"),
              new DocumentKey("persistent_entries", "b"));
      assertRequiredIndexes(
          reopenedDataSource, "persistent_entries", "active", "country", "rank", "score");
      completedValue(reopened.upsert(persistentEntry("b", "AU", 5L, -1.25, true)));
      assertNoOpenResources(reopenedDataSource);
    }
    shutdown(reopenedDataSource);
    assertNoOpenResources(reopenedDataSource);

    TestDriverManagerDataSource twiceReopenedDataSource =
        new TestDriverManagerDataSource(reopenUrl(url));
    twiceReopenedDataSource.trackResources();
    twiceReopenedDataSource.resetDdlStatementCount();
    try (JdbcExecution execution = createExecution(2, 16)) {
      JdbcIndexStore twiceReopened = new JdbcIndexStore(twiceReopenedDataSource, execution);
      completedValue(twiceReopened.initialize(PersistentDocument.class));
      assertThat(twiceReopenedDataSource.resourceSnapshot().ddlStatements()).isZero();
      assertThat(
              completedValue(
                      twiceReopened.query(
                          new IndexQuery(
                              "persistent_entries",
                              List.of(),
                              Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
                              10,
                              Optional.empty())))
                  .documentKeys())
          .containsExactly(
              new DocumentKey("persistent_entries", "b"),
              new DocumentKey("persistent_entries", "a"),
              new DocumentKey("persistent_entries", "c"));
      assertThat(
              completedValue(
                      twiceReopened.query(
                          new IndexQuery(
                              "persistent_entries",
                              List.of(
                                  new IndexQuery.Filter(
                                      "country",
                                      ComparisonOperator.EQUAL,
                                      new IndexValue.StringValue("AU"))),
                              Optional.empty(),
                              10,
                              Optional.empty())))
                  .documentKeys())
          .containsExactly(
              new DocumentKey("persistent_entries", "b"),
              new DocumentKey("persistent_entries", "c"));
      assertRequiredIndexes(
          twiceReopenedDataSource, "persistent_entries", "active", "country", "rank", "score");
      assertNoOpenResources(twiceReopenedDataSource);
    }
    shutdown(twiceReopenedDataSource);
    assertNoOpenResources(twiceReopenedDataSource);
  }

  @Test
  void addsRequiredColumnsAndPreservesIndexesAfterClearAndReopen() throws SQLException {
    String url = fileUrl(temporaryDirectory.resolve("additive"));
    TestDriverManagerDataSource dataSource = new TestDriverManagerDataSource(url);
    dataSource.trackResources();
    execute(
        dataSource,
        "CREATE TABLE \"schema_entries\" ("
            + "\"document_id\" "
            + stringColumnType()
            + " NOT NULL PRIMARY KEY, "
            + "\"country\" "
            + stringColumnType()
            + " NOT NULL)");

    try (JdbcExecution execution = createExecution(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(ExpandedSchemaDocument.class));
      completedValue(
          store.upsert(
              entry(
                  "schema_entries",
                  "one",
                  Map.of(
                      "country", new IndexValue.StringValue("NZ"),
                      "rank", new IndexValue.LongValue(1L)))));
      completedValue(store.clear("schema_entries"));
    }

    assertThat(columnNames(dataSource, "schema_entries"))
        .contains("document_id", "country", "rank");
    assertThat(indexColumns(dataSource, "schema_entries"))
        .containsEntry("idx_schema_entries_country", List.of("country"))
        .containsEntry("idx_schema_entries_rank", List.of("rank"));
    assertThat(rowCount(dataSource, "schema_entries")).isZero();
    assertNoOpenResources(dataSource);
    shutdown(dataSource);
    assertNoOpenResources(dataSource);

    TestDriverManagerDataSource reopened = new TestDriverManagerDataSource(reopenUrl(url));
    reopened.trackResources();
    reopened.resetDdlStatementCount();
    try (JdbcExecution execution = createExecution(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(reopened, execution);
      completedValue(store.initialize(ExpandedSchemaDocument.class));
      assertThat(reopened.resourceSnapshot().ddlStatements()).isZero();
      assertThat(indexColumns(reopened, "schema_entries"))
          .containsEntry("idx_schema_entries_country", List.of("country"))
          .containsEntry("idx_schema_entries_rank", List.of("rank"));
      assertThat(rowCount(reopened, "schema_entries")).isZero();
      assertNoOpenResources(reopened);
    }
    shutdown(reopened);
    assertNoOpenResources(reopened);
  }

  @Test
  void rejectsAddingRequiredColumnsToAPopulatedTableWithoutChangingIt() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("populated");
    dataSource.trackResources();
    try (JdbcExecution execution = createExecution(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(BaseMigrationDocument.class));
      completedValue(
          store.upsert(
              entry(
                  "migration_entries",
                  "existing",
                  Map.of("country", new IndexValue.StringValue("NZ")))));
    }

    try (JdbcExecution execution = createExecution(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      assertThat(completedFailure(store.initialize(ExpandedMigrationDocument.class)))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessageContaining("requires a rebuild");
    }

    assertThat(columnNames(dataSource, "migration_entries"))
        .contains("document_id", "country")
        .doesNotContain("rank");
    assertThat(rowCount(dataSource, "migration_entries")).isEqualTo(1L);
    assertNoOpenResources(dataSource);
    shutdown(dataSource);
    assertNoOpenResources(dataSource);
  }

  @Test
  void rejectsIncompatibleColumnsPrimaryKeysAndIndexes() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("incompatible");
    dataSource.trackResources();
    execute(
        dataSource,
        "CREATE TABLE \"wrong_type_entries\" (\"document_id\" "
            + stringColumnType()
            + " NOT NULL PRIMARY KEY, \"rank\" "
            + stringColumnType()
            + " NOT NULL)");
    execute(
        dataSource,
        "CREATE TABLE \"nullable_entries\" (\"document_id\" "
            + stringColumnType()
            + " NOT NULL PRIMARY KEY, \"rank\" "
            + longColumnType()
            + ")");
    execute(
        dataSource,
        "CREATE TABLE \"missing_pk_entries\" (\"document_id\" "
            + stringColumnType()
            + " NOT NULL, \"rank\" "
            + longColumnType()
            + " NOT NULL)");
    execute(
        dataSource,
        "CREATE TABLE \"wrong_index_entries\" (\"document_id\" "
            + stringColumnType()
            + " NOT NULL PRIMARY KEY, \"rank\" "
            + longColumnType()
            + " NOT NULL, \"country\" "
            + stringColumnType()
            + " NOT NULL)");
    execute(
        dataSource,
        "CREATE INDEX \"idx_wrong_index_entries_rank\" ON \"wrong_index_entries\" (\"country\")");
    execute(
        dataSource,
        "CREATE TABLE \"unique_index_entries\" (\"document_id\" "
            + stringColumnType()
            + " NOT NULL PRIMARY KEY, \"rank\" "
            + longColumnType()
            + " NOT NULL)");
    execute(
        dataSource,
        "CREATE UNIQUE INDEX \"idx_unique_index_entries_rank\" ON \"unique_index_entries\" (\"rank\")");

    try (JdbcExecution execution = createExecution(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      assertIncompatible(store.initialize(WrongTypeDocument.class), "rank");
      assertIncompatible(store.initialize(NullableDocument.class), "rank");
      assertIncompatible(store.initialize(MissingPrimaryKeyDocument.class), "document_id");
      assertIncompatible(store.initialize(WrongIndexDocument.class), "index for rank");
      assertIncompatible(store.initialize(UniqueIndexDocument.class), "index for rank");
    }
    assertNoOpenResources(dataSource);
    shutdown(dataSource);
    assertNoOpenResources(dataSource);
  }

  @Test
  void rejectsInvalidIdentifiersAndTimestampBeforeSchemaMutation() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("unsupported_metadata");
    dataSource.trackResources();
    try (JdbcExecution execution = createExecution(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      assertThatIllegalArgumentException()
          .isThrownBy(() -> store.initialize(InvalidIdentifierDocument.class))
          .withMessageContaining("must match [A-Za-z_][A-Za-z0-9_]*");
      assertThat(completedFailure(store.initialize(TimestampDocument.class)))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessageContaining("does not support indexed field type TIMESTAMP");
    }
    assertThat(tableExists(dataSource, "invalid-name")).isFalse();
    assertThat(tableExists(dataSource, "timestamp_entries")).isFalse();
    assertNoOpenResources(dataSource);
    shutdown(dataSource);
    assertNoOpenResources(dataSource);
  }

  @Test
  void bindsDocumentIdsAndFilterValuesAsParameters() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("parameters");
    String documentId = "id' OR '1'='1";
    String country = "NZ' OR '1'='1";
    try (JdbcExecution execution = createExecution(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(ParameterDocument.class));
      completedValue(
          store.upsert(
              entry(
                  "parameter_entries",
                  documentId,
                  Map.of("country", new IndexValue.StringValue(country)))));
      IndexPage page =
          completedValue(
              store.query(
                  new IndexQuery(
                      "parameter_entries",
                      List.of(
                          new IndexQuery.Filter(
                              "country",
                              ComparisonOperator.EQUAL,
                              new IndexValue.StringValue(country))),
                      Optional.empty(),
                      10,
                      Optional.empty())));
      assertThat(page.documentKeys())
          .containsExactly(new DocumentKey("parameter_entries", documentId));
      completedValue(store.delete(new DocumentKey("parameter_entries", documentId)));
      assertThat(completedValue(store.query(unsortedQuery("parameter_entries"))).documentKeys())
          .isEmpty();
    }
    shutdown(dataSource);
  }

  @Test
  void recoversAfterConnectionAndStatementFailuresWithoutLeakingResources() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("failure_cleanup");
    dataSource.trackResources();
    try (JdbcExecution execution = createExecution(2, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(ParameterDocument.class));

      SQLException connectionFailure = new SQLException("jdbc:secret-connection", "08006");
      dataSource.failConnections(connectionFailure);
      CompletionStage<IndexPage> failedConnection = store.query(unsortedQuery("parameter_entries"));
      assertThat(failedConnection).isNotNull();
      assertThat(completedFailure(failedConnection))
          .isInstanceOf(StorageException.Unavailable.class)
          .hasMessage("JDBC query failed")
          .hasCause(connectionFailure);
      dataSource.clearConnectionFailure();
      assertThat(completedValue(store.query(unsortedQuery("parameter_entries"))).documentKeys())
          .isEmpty();
      assertNoOpenResources(dataSource);

      SQLException statementFailure = new SQLException("SELECT secret FROM hidden", "42000");
      dataSource.failNextStatementExecution(statementFailure);
      CompletionStage<IndexPage> failedStatement = store.query(unsortedQuery("parameter_entries"));
      assertThat(failedStatement).isNotNull();
      assertThat(completedFailure(failedStatement))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessage("JDBC query failed")
          .hasCause(statementFailure);
      assertThat(completedValue(store.query(unsortedQuery("parameter_entries"))).documentKeys())
          .isEmpty();
      assertNoOpenResources(dataSource);
    }
    shutdown(dataSource);
    assertNoOpenResources(dataSource);
  }

  @Test
  void closesResourcesAfterConstraintFailuresAndAllowsSubsequentWrites() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("constraint_cleanup");
    dataSource.trackResources();
    execute(
        dataSource,
        "CREATE TABLE \"constraint_entries\" (\"document_id\" "
            + stringColumnType()
            + " NOT NULL PRIMARY KEY, \"rank\" "
            + longColumnType()
            + " NOT NULL CHECK (\"rank\" >= 0))");
    try (JdbcExecution execution = createExecution(2, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(ConstraintDocument.class));
      Throwable failure =
          completedFailure(
              store.upsert(
                  entry(
                      "constraint_entries",
                      "invalid",
                      Map.of("rank", new IndexValue.LongValue(-1L)))));
      assertThat(failure)
          .isInstanceOf(StorageException.Operation.class)
          .hasMessage("JDBC upsert failed")
          .hasCauseInstanceOf(SQLException.class);
      assertNoOpenResources(dataSource);

      completedValue(
          store.upsert(
              entry("constraint_entries", "valid", Map.of("rank", new IndexValue.LongValue(1L)))));
      assertThat(completedValue(store.query(unsortedQuery("constraint_entries"))).documentKeys())
          .containsExactly(new DocumentKey("constraint_entries", "valid"));
      assertNoOpenResources(dataSource);
    }
    shutdown(dataSource);
    assertNoOpenResources(dataSource);
  }

  @Test
  @Timeout(60)
  void preservesCorrectnessAcrossControlledConcurrentReadsAndWrites() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("concurrent_parity");
    dataSource.trackResources();
    ExecutorService callers = Executors.newFixedThreadPool(8);
    try (JdbcExecution execution = createExecution(4, 64)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(ConcurrentDocument.class));
      CountDownLatch start = new CountDownLatch(1);
      List<CompletableFuture<CompletionStage<?>>> submissions = new ArrayList<>();
      for (int operation = 0; operation < 40; operation++) {
        int current = operation;
        submissions.add(
            CompletableFuture.supplyAsync(
                () -> {
                  await(start);
                  if (current < 32) {
                    return store.upsert(
                        entry(
                            "concurrent_entries",
                            "document-" + String.format("%02d", current),
                            Map.of("rank", new IndexValue.LongValue(current % 7L))));
                  }
                  return store.query(
                      new IndexQuery(
                          "concurrent_entries",
                          List.of(),
                          Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
                          100,
                          Optional.empty()));
                },
                callers));
      }
      start.countDown();
      List<CompletionStage<?>> operations =
          submissions.stream().map(JdbcBackendTest::completedValue).toList();
      CompletableFuture<?>[] futures =
          operations.stream()
              .map(CompletionStage::toCompletableFuture)
              .toArray(CompletableFuture[]::new);
      completedValue(CompletableFuture.allOf(futures));
      operations.subList(32, 40).stream()
          .map(JdbcBackendTest::completedValue)
          .map(IndexPage.class::cast)
          .forEach(
              page ->
                  assertThat(page.documentKeys())
                      .doesNotHaveDuplicates()
                      .allMatch(key -> key.collection().equals("concurrent_entries")));

      IndexPage finalPage =
          completedValue(
              store.query(
                  new IndexQuery(
                      "concurrent_entries",
                      List.of(),
                      Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
                      100,
                      Optional.empty())));
      assertThat(finalPage.documentKeys()).hasSize(32).doesNotHaveDuplicates();
      assertNoOpenResources(dataSource);
    } finally {
      callers.shutdownNow();
    }
    shutdown(dataSource);
    assertNoOpenResources(dataSource);
  }

  @Test
  @Timeout(60)
  void repeatsOneHundredOperationsWithoutLeakingResourcesOrState() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("repetition");
    dataSource.trackResources();
    try (JdbcExecution execution = createExecution(4, 64)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(ConcurrentDocument.class));
      for (int index = 0; index < 40; index++) {
        completedValue(
            store.upsert(
                entry(
                    "concurrent_entries",
                    "document-" + String.format("%02d", index),
                    Map.of("rank", new IndexValue.LongValue(index % 7L)))));
      }
      for (int traversal = 0; traversal < 5; traversal++) {
        List<DocumentKey> traversed = new ArrayList<>();
        Optional<IndexCursor> cursor = Optional.empty();
        for (int page = 0; page < 6; page++) {
          IndexPage result =
              completedValue(
                  store.query(
                      new IndexQuery(
                          "concurrent_entries",
                          List.of(),
                          Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
                          7,
                          cursor)));
          traversed.addAll(result.documentKeys());
          cursor = result.nextCursor();
        }
        assertThat(cursor).isEmpty();
        assertThat(traversed).hasSize(40).doesNotHaveDuplicates();
      }
      for (int index = 0; index < 30; index++) {
        completedValue(
            store.delete(
                new DocumentKey("concurrent_entries", "document-" + String.format("%02d", index))));
      }
      assertThat(
              completedValue(
                      store.query(
                          new IndexQuery(
                              "concurrent_entries",
                              List.of(),
                              Optional.empty(),
                              100,
                              Optional.empty())))
                  .documentKeys())
          .containsExactly(
              new DocumentKey("concurrent_entries", "document-30"),
              new DocumentKey("concurrent_entries", "document-31"),
              new DocumentKey("concurrent_entries", "document-32"),
              new DocumentKey("concurrent_entries", "document-33"),
              new DocumentKey("concurrent_entries", "document-34"),
              new DocumentKey("concurrent_entries", "document-35"),
              new DocumentKey("concurrent_entries", "document-36"),
              new DocumentKey("concurrent_entries", "document-37"),
              new DocumentKey("concurrent_entries", "document-38"),
              new DocumentKey("concurrent_entries", "document-39"));
      assertNoOpenResources(dataSource);
      assertThat(dataSource.resourceSnapshot().peakConnections()).isLessThanOrEqualTo(4);
    }
    shutdown(dataSource);
    assertNoOpenResources(dataSource);
  }

  protected final TestDriverManagerDataSource dataSource(String databaseName) {
    return new TestDriverManagerDataSource(fileUrl(temporaryDirectory.resolve(databaseName)));
  }

  protected static IndexEntry entry(
      String collection, String documentId, Map<String, IndexValue> values) {
    return new IndexEntry(new DocumentKey(collection, documentId), values);
  }

  protected static IndexQuery unsortedQuery(String collection) {
    return new IndexQuery(collection, List.of(), Optional.empty(), 10, Optional.empty());
  }

  protected static void execute(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  protected static List<String> columnNames(DataSource dataSource, String table)
      throws SQLException {
    List<String> columns = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet resultSet =
          metadata.getColumns(connection.getCatalog(), connection.getSchema(), table, null)) {
        while (resultSet.next()) {
          if (table.equals(resultSet.getString("TABLE_NAME"))) {
            columns.add(resultSet.getString("COLUMN_NAME"));
          }
        }
      }
    }
    return List.copyOf(columns);
  }

  protected static Map<String, List<String>> indexColumns(DataSource dataSource, String table)
      throws SQLException {
    Map<String, List<String>> indexes = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet resultSet =
          metadata.getIndexInfo(
              connection.getCatalog(), connection.getSchema(), table, false, false)) {
        while (resultSet.next()) {
          String index = resultSet.getString("INDEX_NAME");
          String column = resultSet.getString("COLUMN_NAME");
          if (index != null && column != null) {
            indexes.computeIfAbsent(index, ignored -> new ArrayList<>()).add(column);
          }
        }
      }
    }
    return Map.copyOf(indexes);
  }

  protected static long rowCount(DataSource dataSource, String table) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getLong(1);
    }
  }

  protected static boolean tableExists(DataSource dataSource, String table) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet resultSet =
          metadata.getTables(connection.getCatalog(), connection.getSchema(), null, null)) {
        while (resultSet.next()) {
          if (table.equals(resultSet.getString("TABLE_NAME"))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  protected static void assertNoOpenResources(TestDriverManagerDataSource dataSource) {
    TestDriverManagerDataSource.ResourceSnapshot snapshot = dataSource.resourceSnapshot();
    assertThat(snapshot.activeConnections()).as("active JDBC connections").isZero();
    assertThat(snapshot.activeStatements()).as("active JDBC statements").isZero();
    assertThat(snapshot.activeResultSets()).as("active JDBC result sets").isZero();
    assertThat(snapshot.activeMetadataResultSets()).as("active JDBC metadata result sets").isZero();
  }

  private static void assertRequiredIndexes(
      DataSource dataSource, String collection, String... fields) throws SQLException {
    Map<String, List<String>> indexes = indexColumns(dataSource, collection);
    for (String field : fields) {
      assertThat(indexes).containsEntry("idx_" + collection + "_" + field, List.of(field));
    }
  }

  private static IndexEntry persistentEntry(
      String documentId, String country, long rank, double score, boolean active) {
    return entry(
        "persistent_entries",
        documentId,
        Map.of(
            "active", new IndexValue.BooleanValue(active),
            "country", new IndexValue.StringValue(country),
            "rank", new IndexValue.LongValue(rank),
            "score", new IndexValue.DoubleValue(score)));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("concurrent JDBC operations did not start in time");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("concurrent JDBC operation was interrupted", failure);
    }
  }

  protected static void assertIncompatible(CompletionStage<?> stage, String detail) {
    assertThat(completedFailure(stage))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessageContaining("is incompatible")
        .hasMessageContaining(detail);
  }

  protected static <T> T completedValue(CompletionStage<T> stage) {
    var future = stage.toCompletableFuture();
    assertThat(future).succeedsWithin(Duration.ofSeconds(10));
    return future.join();
  }

  protected static Throwable completedFailure(CompletionStage<?> stage) {
    return completedFailure(stage, Duration.ofSeconds(10));
  }

  protected static Throwable completedFailure(CompletionStage<?> stage, Duration timeout) {
    try {
      stage
          .toCompletableFuture()
          .orTimeout(timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
          .join();
      throw new AssertionError("stage completed successfully");
    } catch (CompletionException failure) {
      return failure.getCause();
    }
  }

  @Document("persistent_entries")
  private static final class PersistentDocument {
    @Index private String country;
    @Index private long rank;
    @Index private double score;
    @Index private boolean active;
  }

  @Document("schema_entries")
  private static final class ExpandedSchemaDocument {
    @Index private String country;
    @Index private long rank;
  }

  @Document("migration_entries")
  private static final class BaseMigrationDocument {
    @Index private String country;
  }

  @Document("migration_entries")
  private static final class ExpandedMigrationDocument {
    @Index private String country;
    @Index private long rank;
  }

  @Document("wrong_type_entries")
  private static final class WrongTypeDocument {
    @Index private long rank;
  }

  @Document("nullable_entries")
  private static final class NullableDocument {
    @Index private long rank;
  }

  @Document("missing_pk_entries")
  private static final class MissingPrimaryKeyDocument {
    @Index private long rank;
  }

  @Document("wrong_index_entries")
  private static final class WrongIndexDocument {
    @Index private long rank;
  }

  @Document("unique_index_entries")
  private static final class UniqueIndexDocument {
    @Index private long rank;
  }

  @Document("constraint_entries")
  private static final class ConstraintDocument {
    @Index private long rank;
  }

  @Document("concurrent_entries")
  private static final class ConcurrentDocument {
    @Index private long rank;
  }

  @Document("invalid-name")
  private static final class InvalidIdentifierDocument {
    @Index private String country;
  }

  @Document("timestamp_entries")
  private static final class TimestampDocument {
    @Index private Instant createdAt;
  }

  @Document("parameter_entries")
  private static final class ParameterDocument {
    @Index private String country;
  }
}
