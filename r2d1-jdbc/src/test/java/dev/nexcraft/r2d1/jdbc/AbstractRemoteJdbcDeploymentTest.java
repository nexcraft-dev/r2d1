package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentCursor;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Shared multi-instance deployment coverage for remote JDBC servers. */
abstract class AbstractRemoteJdbcDeploymentTest {

  private static final String COLLECTION = "deployment_entries";

  @TempDir Path temporaryDirectory;

  private final List<Instance> instances = new ArrayList<>();
  private @Nullable TestJdbcServer server;

  protected abstract TestJdbcServer.Backend backend();

  protected JdbcExecution createExecution() {
    return JdbcExecution.create(4, 64);
  }

  protected final JdbcExecution createVirtualExecution() {
    return JdbcExecution.create(new JdbcExecutionConfig(JdbcExecutionMode.VIRTUAL_THREAD, 4, 64));
  }

  @BeforeEach
  void startServer() throws SQLException {
    server = TestJdbcServer.start(backend(), temporaryDirectory.resolve("server"));
  }

  @AfterEach
  void closeResources() {
    for (int index = instances.size() - 1; index >= 0; index--) {
      instances.get(index).close();
    }
    instances.clear();
    TestJdbcServer current = server;
    server = null;
    if (current != null) {
      current.close();
    }
  }

  @Test
  @Timeout(60)
  void concurrentFreshSchemaInitializationLeavesAnExactlyCompatibleSchema() throws SQLException {
    List<Instance> stores = createInstances(3);

    List<Throwable> failures = settleConcurrentInitializations(stores);
    assertThat(failures).hasSizeLessThan(3).allMatch(StorageException.class::isInstance);

    List<Instance> observers = createInstances(3);
    initializeConcurrently(observers);

    assertThat(columnNames(observers.getFirst().dataSource()))
        .containsExactlyInAnyOrder("document_id", "active", "country", "rank");
    assertThat(indexColumns(observers.getFirst().dataSource()))
        .containsEntry("idx_deployment_entries_active", List.of("active"))
        .containsEntry("idx_deployment_entries_country", List.of("country"))
        .containsEntry("idx_deployment_entries_rank", List.of("rank"));
    instances.forEach(AbstractRemoteJdbcDeploymentTest::assertNoOpenResources);
  }

  @Test
  @Timeout(60)
  void concurrentAdditiveInitializationLeavesAnExactlyCompatibleSchema() throws SQLException {
    TestDriverManagerDataSource bootstrapDataSource = dataSource();
    try (JdbcExecution bootstrapExecution = createExecution()) {
      await(
          new JdbcIndexStore(bootstrapDataSource, bootstrapExecution)
              .initialize(BaseDeploymentDocument.class));
    }

    List<Instance> stores = createInstances(3);
    List<Throwable> failures = settleConcurrentInitializations(stores);
    assertThat(failures).hasSizeLessThan(3).allMatch(StorageException.class::isInstance);

    List<Instance> observers = createInstances(3);
    initializeConcurrently(observers);

    assertThat(columnNames(observers.getFirst().dataSource()))
        .containsExactlyInAnyOrder("document_id", "active", "country", "rank");
    assertThat(indexColumns(observers.getFirst().dataSource()))
        .containsEntry("idx_deployment_entries_active", List.of("active"))
        .containsEntry("idx_deployment_entries_country", List.of("country"))
        .containsEntry("idx_deployment_entries_rank", List.of("rank"));
    instances.forEach(AbstractRemoteJdbcDeploymentTest::assertNoOpenResources);
  }

  @Test
  void acceptsOnlyExactlyCompatibleObjectsAfterAmbiguousDdlFailures() throws SQLException {
    SQLException reportedFailure = new SQLException("ambiguous DDL result", "42000");
    Instance table = createInstance();
    table.dataSource().failAfterNextStatementExecution(reportedFailure);
    await(table.store().initialize(AmbiguousTableDocument.class));

    bootstrap(AmbiguousColumnBaseDocument.class);
    Instance column = createInstance();
    column
        .dataSource()
        .failAfterNextStatementExecution(new SQLException("ambiguous DDL result", "42000"));
    await(column.store().initialize(AmbiguousColumnDocument.class));

    bootstrap(AmbiguousIndexDocument.class);
    dropAmbiguousIndex(dataSource());
    Instance index = createInstance();
    index
        .dataSource()
        .failAfterNextStatementExecution(new SQLException("ambiguous DDL result", "42000"));
    await(index.store().initialize(AmbiguousIndexDocument.class));

    Instance absent = createInstance();
    absent
        .dataSource()
        .failNextStatementExecution(new SQLException("DDL did not execute", "42000"));
    assertThat(completedFailure(absent.store().initialize(AbsentDdlDocument.class)))
        .isInstanceOf(StorageException.Operation.class)
        .hasCauseInstanceOf(SQLException.class);
    assertThat(absentDdlTableExists(absent.dataSource())).isFalse();
    instances.forEach(AbstractRemoteJdbcDeploymentTest::assertNoOpenResources);
  }

  @Test
  void rejectsIncompatibleSharedSchemaFromEveryInstance() {
    TestDriverManagerDataSource bootstrapDataSource = dataSource();
    try (JdbcExecution bootstrapExecution = createExecution()) {
      await(
          new JdbcIndexStore(bootstrapDataSource, bootstrapExecution)
              .initialize(WrongRankDeploymentDocument.class));
    }

    List<Instance> stores = createInstances(3);
    for (Instance instance : stores) {
      assertThat(completedFailure(instance.store().initialize(DeploymentDocument.class)))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessageContaining("is incompatible")
          .hasMessageContaining("rank");
    }
    stores.forEach(AbstractRemoteJdbcDeploymentTest::assertNoOpenResources);
  }

  @Test
  @Timeout(60)
  void sharesRowsConcurrentMutationsCursorsAndClearAcrossInstances() {
    List<Instance> stores = createInstances(3);
    initializeSequentially(stores, DeploymentDocument.class);
    stores.forEach(instance -> instance.dataSource().resetConnectionAttempts());

    await(stores.getFirst().store().upsert(entry("bridge", 1L)));
    assertThat(stores.getFirst().dataSource().connectionAttempts()).isEqualTo(1);
    assertThat(await(stores.get(1).store().query(query(10, null))).documentKeys())
        .containsExactly(key("bridge"));
    assertThat(stores.get(1).dataSource().connectionAttempts()).isEqualTo(1);
    await(stores.get(2).store().delete(key("bridge")));
    assertThat(stores.get(2).dataSource().connectionAttempts()).isEqualTo(1);
    assertThat(await(stores.getFirst().store().query(query(10, null))).documentKeys()).isEmpty();
    assertThat(stores.getFirst().dataSource().connectionAttempts()).isEqualTo(2);

    List<CompletionStage<@Nullable Void>> writes = new ArrayList<>();
    for (int index = 0; index < 30; index++) {
      writes.add(
          stores
              .get(index % stores.size())
              .store()
              .upsert(entry("document-" + String.format("%02d", index), index)));
    }
    awaitAll(writes);

    awaitAll(
        List.of(
            stores.getFirst().store().upsert(entry("shared", 100L)),
            stores.get(1).store().upsert(entry("shared", 101L)),
            stores.get(2).store().upsert(entry("shared", 102L))));
    IndexPage afterWrites = await(stores.getFirst().store().query(query(100, null)));
    assertThat(afterWrites.documentKeys())
        .hasSize(31)
        .doesNotHaveDuplicates()
        .contains(key("shared"));

    List<CompletionStage<@Nullable Void>> deletes = new ArrayList<>();
    for (int index = 0; index < 15; index++) {
      deletes.add(
          stores
              .get(index % stores.size())
              .store()
              .delete(key("document-" + String.format("%02d", index))));
    }
    awaitAll(deletes);

    List<DocumentKey> firstTraversal = traverse(stores.getFirst().store());
    assertThat(firstTraversal).hasSize(16).doesNotHaveDuplicates().contains(key("shared"));
    assertThat(traverse(stores.get(1).store())).containsExactlyElementsOf(firstTraversal);
    assertThat(traverse(stores.get(2).store())).containsExactlyElementsOf(firstTraversal);

    await(stores.get(1).store().clear(COLLECTION));
    assertThat(await(stores.getFirst().store().query(query(10, null))).documentKeys()).isEmpty();
    assertThat(await(stores.get(2).store().query(query(10, null))).documentKeys()).isEmpty();
    stores.forEach(AbstractRemoteJdbcDeploymentTest::assertNoOpenResources);
  }

  @Test
  @Timeout(60)
  void reportsOneUnavailableAttemptAndRecoversAfterSamePortRestart() throws SQLException {
    Instance instance = createInstance();
    await(instance.store().initialize(DeploymentDocument.class));
    await(instance.store().upsert(entry("persistent", 7L)));

    server().stop();
    instance.dataSource().resetConnectionAttempts();
    assertThat(completedFailure(instance.store().query(query(10, null))))
        .isInstanceOf(StorageException.Unavailable.class)
        .hasMessage("JDBC query failed")
        .hasCauseInstanceOf(SQLException.class);
    assertThat(instance.dataSource().connectionAttempts()).isEqualTo(1);
    assertNoOpenResources(instance);

    server().restart();
    assertThat(await(instance.store().query(query(10, null))).documentKeys())
        .containsExactly(key("persistent"));
    assertThat(instance.dataSource().connectionAttempts()).isEqualTo(2);
    assertNoOpenResources(instance);
  }

  @Test
  void rebuildsFromAuthoritativeDocumentsAndPublishesResultsToAnotherInstance() {
    List<Instance> stores = createInstances(2);
    initializeSequentially(stores, RebuildDocument.class);
    InMemoryDocumentStore documents = new InMemoryDocumentStore();
    RebuildCodec codec = new RebuildCodec();
    documents.store(codec, new RebuildDocument("a", "NZ", 1L));
    documents.store(codec, new RebuildDocument("b", "AU", 2L));
    documents.store(codec, new RebuildDocument("c", "US", 3L));
    await(stores.getFirst().store().upsert(staleRebuildEntry()));

    R2D1Collection<RebuildDocument> collection =
        new PersistenceCollectionFactory(
                documents, stores.getFirst().store(), codec, stores.getFirst().store()::initialize)
            .create(RebuildDocument.class);
    collection.rebuildIndex();

    assertThat(await(stores.get(1).store().query(rebuildQuery())).documentKeys())
        .containsExactly(
            new DocumentKey("rebuild_entries", "a"),
            new DocumentKey("rebuild_entries", "b"),
            new DocumentKey("rebuild_entries", "c"));
    stores.forEach(AbstractRemoteJdbcDeploymentTest::assertNoOpenResources);
  }

  private List<Instance> createInstances(int count) {
    List<Instance> created = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      created.add(createInstance());
    }
    return List.copyOf(created);
  }

  private Instance createInstance() {
    TestDriverManagerDataSource dataSource = dataSource();
    dataSource.trackResources();
    JdbcExecution execution = createExecution();
    Instance instance =
        new Instance(dataSource, execution, new JdbcIndexStore(dataSource, execution));
    instances.add(instance);
    return instance;
  }

  private TestDriverManagerDataSource dataSource() {
    return new TestDriverManagerDataSource(server().jdbcUrl());
  }

  private TestJdbcServer server() {
    return Objects.requireNonNull(server, "test JDBC server is not started");
  }

  private void bootstrap(Class<?> documentType) {
    TestDriverManagerDataSource bootstrapDataSource = dataSource();
    try (JdbcExecution bootstrapExecution = createExecution()) {
      await(new JdbcIndexStore(bootstrapDataSource, bootstrapExecution).initialize(documentType));
    }
  }

  private static void initializeSequentially(List<Instance> stores, Class<?> documentType) {
    stores.forEach(instance -> await(instance.store().initialize(documentType)));
  }

  private static void initializeConcurrently(List<Instance> stores) {
    List<CompletionStage<@Nullable Void>> initializations =
        stores.stream()
            .map(instance -> instance.store().initialize(DeploymentDocument.class))
            .toList();
    awaitAll(initializations);
  }

  private static List<Throwable> settleConcurrentInitializations(List<Instance> stores) {
    List<CompletionStage<@Nullable Void>> initializations =
        stores.stream()
            .map(instance -> instance.store().initialize(DeploymentDocument.class))
            .toList();
    CompletableFuture<?>[] futures =
        initializations.stream()
            .map(CompletionStage::toCompletableFuture)
            .toArray(CompletableFuture[]::new);
    await(CompletableFuture.allOf(futures).handle((ignored, failure) -> null));
    return initializations.stream()
        .filter(stage -> stage.toCompletableFuture().isCompletedExceptionally())
        .map(AbstractRemoteJdbcDeploymentTest::completedFailure)
        .toList();
  }

  private static void awaitAll(List<? extends CompletionStage<@Nullable Void>> stages) {
    CompletableFuture<?>[] futures =
        stages.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new);
    await(CompletableFuture.allOf(futures));
  }

  private static List<DocumentKey> traverse(JdbcIndexStore store) {
    List<DocumentKey> keys = new ArrayList<>();
    @Nullable IndexCursor cursor = null;
    do {
      IndexPage page = await(store.query(query(4, cursor)));
      keys.addAll(page.documentKeys());
      cursor = page.nextCursor().orElse(null);
    } while (cursor != null);
    return List.copyOf(keys);
  }

  private static IndexEntry entry(String id, long rank) {
    return new IndexEntry(
        key(id),
        Map.of(
            "active", new IndexValue.BooleanValue(true),
            "country", new IndexValue.StringValue("NZ"),
            "rank", new IndexValue.LongValue(rank)));
  }

  private static DocumentKey key(String id) {
    return new DocumentKey(COLLECTION, id);
  }

  private static IndexQuery query(int limit, @Nullable IndexCursor cursor) {
    return new IndexQuery(
        COLLECTION,
        List.of(),
        Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
        limit,
        Optional.ofNullable(cursor));
  }

  private static IndexEntry staleRebuildEntry() {
    return new IndexEntry(
        new DocumentKey("rebuild_entries", "stale"),
        Map.of(
            "country", new IndexValue.StringValue("old"),
            "rank", new IndexValue.LongValue(99L)));
  }

  private static IndexQuery rebuildQuery() {
    return new IndexQuery(
        "rebuild_entries",
        List.of(),
        Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
        10,
        Optional.empty());
  }

  private static void dropAmbiguousIndex(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("DROP INDEX \"idx_ambiguous_index_entries_rank\"");
    }
  }

  private static List<String> columnNames(DataSource dataSource) throws SQLException {
    List<String> columns = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet resultSet =
          metadata.getColumns(connection.getCatalog(), connection.getSchema(), COLLECTION, null)) {
        while (resultSet.next()) {
          if (COLLECTION.equals(resultSet.getString("TABLE_NAME"))) {
            columns.add(resultSet.getString("COLUMN_NAME"));
          }
        }
      }
    }
    return List.copyOf(columns);
  }

  private static Map<String, List<String>> indexColumns(DataSource dataSource) throws SQLException {
    Map<String, List<String>> indexes = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet resultSet =
          metadata.getIndexInfo(
              connection.getCatalog(), connection.getSchema(), COLLECTION, false, false)) {
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

  private static boolean absentDdlTableExists(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet resultSet =
          metadata.getTables(
              connection.getCatalog(), connection.getSchema(), "absent_ddl_entries", null)) {
        while (resultSet.next()) {
          if ("absent_ddl_entries".equals(resultSet.getString("TABLE_NAME"))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static void assertNoOpenResources(Instance instance) {
    TestDriverManagerDataSource.ResourceSnapshot snapshot =
        instance.dataSource().resourceSnapshot();
    assertThat(snapshot.activeConnections()).as("active JDBC connections").isZero();
    assertThat(snapshot.activeStatements()).as("active JDBC statements").isZero();
    assertThat(snapshot.activeResultSets()).as("active JDBC result sets").isZero();
    assertThat(snapshot.activeMetadataResultSets()).as("active JDBC metadata results").isZero();
  }

  private static <T extends @Nullable Object> T await(CompletionStage<T> stage) {
    CompletableFuture<T> future = stage.toCompletableFuture();
    assertThat(future).succeedsWithin(Duration.ofSeconds(20));
    return future.join();
  }

  private static Throwable completedFailure(CompletionStage<? extends @Nullable Object> stage) {
    try {
      stage.toCompletableFuture().orTimeout(20, TimeUnit.SECONDS).join();
      throw new AssertionError("stage completed successfully");
    } catch (CompletionException failure) {
      return Objects.requireNonNull(failure.getCause(), "completion failure has no cause");
    }
  }

  @Document(COLLECTION)
  private record BaseDeploymentDocument(@Index boolean active, @Index String country) {}

  @Document(COLLECTION)
  private record WrongRankDeploymentDocument(
      @Index boolean active, @Index String country, @Index String rank) {}

  @Document("ambiguous_table_entries")
  private record AmbiguousTableDocument(@Index long rank) {}

  @Document("ambiguous_column_entries")
  private record AmbiguousColumnBaseDocument(@Index String country) {}

  @Document("ambiguous_column_entries")
  private record AmbiguousColumnDocument(@Index String country, @Index long rank) {}

  @Document("ambiguous_index_entries")
  private record AmbiguousIndexDocument(@Index long rank) {}

  @Document("absent_ddl_entries")
  private record AbsentDdlDocument(@Index long rank) {}

  @Document(COLLECTION)
  private record DeploymentDocument(
      @Index boolean active, @Index String country, @Index(sortable = true) long rank) {}

  @Document("rebuild_entries")
  private record RebuildDocument(
      @Id String id, @Index String country, @Index(sortable = true) long rank) {}

  private record Instance(
      TestDriverManagerDataSource dataSource, JdbcExecution execution, JdbcIndexStore store) {

    void close() {
      execution.close();
    }
  }

  private static final class InMemoryDocumentStore implements DocumentStore {

    private final Map<DocumentKey, StoredDocument> documents = new LinkedHashMap<>();

    private void store(RebuildCodec codec, RebuildDocument document) {
      documents.put(new DocumentKey("rebuild_entries", document.id()), codec.serialize(document));
    }

    @Override
    public CompletionStage<DocumentPage> list(
        String collection, @Nullable DocumentCursor cursor, int limit) {
      int offset = cursor == null ? 0 : Integer.parseInt(cursor.value());
      List<DocumentKey> matching =
          documents.keySet().stream().filter(key -> key.collection().equals(collection)).toList();
      int end = Math.min(offset + limit, matching.size());
      Optional<DocumentCursor> next =
          end < matching.size()
              ? Optional.of(new DocumentCursor(Integer.toString(end)))
              : Optional.empty();
      return CompletableFuture.completedFuture(
          new DocumentPage(matching.subList(offset, end), next));
    }

    @Override
    public CompletionStage<@Nullable Void> put(DocumentKey key, StoredDocument document) {
      documents.put(key, document);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<StoredDocument> get(DocumentKey key) {
      StoredDocument document = documents.get(key);
      return document == null
          ? CompletableFuture.failedFuture(new DocumentNotFoundException(key))
          : CompletableFuture.completedFuture(document);
    }

    @Override
    public CompletionStage<@Nullable Void> delete(DocumentKey key) {
      documents.remove(key);
      return CompletableFuture.completedFuture(null);
    }
  }

  private static final class RebuildCodec implements DocumentCodec {

    @Override
    public StoredDocument serialize(Object document) {
      RebuildDocument value = (RebuildDocument) document;
      return new StoredDocument(
          (value.id() + "\n" + value.country() + "\n" + value.rank())
              .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public <T> T deserialize(StoredDocument document, Class<T> documentType) {
      String[] values = new String(document.content(), StandardCharsets.UTF_8).split("\\n", -1);
      return documentType.cast(
          new RebuildDocument(values[0], values[1], Long.parseLong(values[2])));
    }
  }
}
