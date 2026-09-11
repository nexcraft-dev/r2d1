package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentKey;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shared schema, persistence, and parameter coverage for built-in JDBC backends. */
abstract class JdbcBackendTest {

  @TempDir protected Path temporaryDirectory;

  protected abstract String fileUrl(Path databasePath);

  protected abstract String stringColumnType();

  protected void assertDatabaseFiles(Path databasePath) {}

  protected String reopenUrl(String url) {
    return url;
  }

  protected void shutdown(DataSource dataSource) throws SQLException {}

  @Test
  void persistsRowsAcrossDataSourceAndExecutionReopen() throws SQLException {
    Path databasePath = temporaryDirectory.resolve("persistent");
    String url = fileUrl(databasePath);
    TestDriverManagerDataSource firstDataSource = new TestDriverManagerDataSource(url);

    try (JdbcExecution execution = JdbcExecution.create(2, 16)) {
      JdbcIndexStore store = new JdbcIndexStore(firstDataSource, execution);
      completedValue(store.initialize(PersistentDocument.class));
      completedValue(
          store.upsert(
              entry(
                  "persistent_entries",
                  "first",
                  Map.of(
                      "active", new IndexValue.BooleanValue(true),
                      "country", new IndexValue.StringValue("NZ"),
                      "rank", new IndexValue.LongValue(10L),
                      "score", new IndexValue.DoubleValue(2.5)))));
    }
    shutdown(firstDataSource);
    assertDatabaseFiles(databasePath);

    TestDriverManagerDataSource reopenedDataSource =
        new TestDriverManagerDataSource(reopenUrl(url));
    try (JdbcExecution execution = JdbcExecution.create(2, 16)) {
      JdbcIndexStore reopened = new JdbcIndexStore(reopenedDataSource, execution);
      completedValue(reopened.initialize(PersistentDocument.class));
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
          .containsExactly(new DocumentKey("persistent_entries", "first"));
    }
    shutdown(reopenedDataSource);
  }

  @Test
  void addsRequiredColumnsAndIndexesToAnEmptyTable() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("additive");
    execute(
        dataSource,
        "CREATE TABLE \"schema_entries\" ("
            + "\"document_id\" "
            + stringColumnType()
            + " NOT NULL PRIMARY KEY, "
            + "\"country\" "
            + stringColumnType()
            + " NOT NULL)");

    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
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
    shutdown(dataSource);
  }

  @Test
  void rejectsAddingRequiredColumnsToAPopulatedTableWithoutChangingIt() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("populated");
    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(BaseMigrationDocument.class));
      completedValue(
          store.upsert(
              entry(
                  "migration_entries",
                  "existing",
                  Map.of("country", new IndexValue.StringValue("NZ")))));
    }

    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      assertThat(completedFailure(store.initialize(ExpandedMigrationDocument.class)))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessageContaining("requires a rebuild");
    }

    assertThat(columnNames(dataSource, "migration_entries"))
        .contains("document_id", "country")
        .doesNotContain("rank");
    assertThat(rowCount(dataSource, "migration_entries")).isEqualTo(1L);
    shutdown(dataSource);
  }

  @Test
  void rejectsIncompatibleColumnsPrimaryKeysAndIndexes() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("incompatible");
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
            + " NOT NULL PRIMARY KEY, \"rank\" BIGINT)");
    execute(
        dataSource,
        "CREATE TABLE \"missing_pk_entries\" (\"document_id\" "
            + stringColumnType()
            + " NOT NULL, \"rank\" BIGINT NOT NULL)");
    execute(
        dataSource,
        "CREATE TABLE \"wrong_index_entries\" (\"document_id\" "
            + stringColumnType()
            + " NOT NULL PRIMARY KEY, \"rank\" BIGINT NOT NULL, \"country\" "
            + stringColumnType()
            + " NOT NULL)");
    execute(
        dataSource,
        "CREATE INDEX \"idx_wrong_index_entries_rank\" ON \"wrong_index_entries\" (\"country\")");

    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      assertIncompatible(store.initialize(WrongTypeDocument.class), "rank");
      assertIncompatible(store.initialize(NullableDocument.class), "rank");
      assertIncompatible(store.initialize(MissingPrimaryKeyDocument.class), "document_id");
      assertIncompatible(store.initialize(WrongIndexDocument.class), "index for rank");
    }
    shutdown(dataSource);
  }

  @Test
  void rejectsInvalidIdentifiersAndTimestampBeforeSchemaMutation() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("unsupported_metadata");
    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
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
    shutdown(dataSource);
  }

  @Test
  void bindsDocumentIdsAndFilterValuesAsParameters() throws SQLException {
    TestDriverManagerDataSource dataSource = dataSource("parameters");
    String documentId = "id' OR '1'='1";
    String country = "NZ' OR '1'='1";
    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
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
