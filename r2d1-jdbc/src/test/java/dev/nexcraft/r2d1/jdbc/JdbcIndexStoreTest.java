package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDatabase;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDialects;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class JdbcIndexStoreTest {

  @Test
  void detectsTheBuiltInH2DialectByExactProductName() throws SQLException {
    DatabaseMetaData h2 = proxy(DatabaseMetaData.class, new MetadataHandler("H2"));
    DatabaseMetaData hsqldb =
        proxy(DatabaseMetaData.class, new MetadataHandler("HSQL Database Engine"));
    DatabaseMetaData sqlite = proxy(DatabaseMetaData.class, new MetadataHandler("SQLite"));
    DatabaseMetaData lowerCase = proxy(DatabaseMetaData.class, new MetadataHandler("h2"));

    assertThat(JdbcDialects.detect(h2).getClass().getSimpleName()).isEqualTo("H2Dialect");
    assertThat(JdbcDialects.detect(hsqldb).getClass().getSimpleName()).isEqualTo("HsqldbDialect");
    assertThat(JdbcDialects.detect(sqlite).getClass().getSimpleName()).isEqualTo("SqliteDialect");
    assertThatThrownBy(() -> JdbcDialects.detect(lowerCase))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("JDBC database is not supported");
  }

  @Test
  void initializesOnceAndRoutesEveryOperationThroughJdbcExecution() {
    RecordingDataSource dataSource = new RecordingDataSource("Test Database");
    RecordingDialect dialect = new RecordingDialect();
    IndexPage expectedPage =
        new IndexPage(List.of(new DocumentKey("users", "user-1")), Optional.empty());
    dialect.page = expectedPage;

    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution, ignored -> dialect);

      CompletionStage<@Nullable Void> firstInitialization = store.initialize(User.class);
      CompletionStage<@Nullable Void> secondInitialization = store.initialize(User.class);
      assertThat(secondInitialization).isSameAs(firstInitialization);
      completedValue(firstInitialization);

      completedValue(store.upsert(entry()));
      assertThat(completedValue(store.query(query()))).isEqualTo(expectedPage);
      completedValue(store.delete(new DocumentKey("users", "user-1")));
      completedValue(store.clear("users"));

      assertThat(dialect.operations)
          .containsExactly("initialize", "upsert", "query", "delete", "clear");
      assertThat(dialect.operationThreads)
          .allSatisfy(
              thread -> {
                assertThat(thread.getName()).startsWith("r2d1-jdbc-");
                assertThat(thread.isVirtual()).isFalse();
              });
      assertThat(dataSource.connections)
          .hasSize(5)
          .allSatisfy(connection -> assertThat(connection.closed).isTrue());
      assertThat(dialect.metadata.indexedFields())
          .extracting(JdbcMetadata.IndexedField::name)
          .containsExactly("active", "country", "createdAt", "rank", "score");
    }
  }

  @Test
  void requiresInitializationAndValidatesPublicInputs() {
    RecordingDataSource dataSource = new RecordingDataSource("Test Database");
    try (JdbcExecution execution = JdbcExecution.create(1, 1)) {
      JdbcIndexStore store =
          new JdbcIndexStore(dataSource, execution, ignored -> new RecordingDialect());

      assertThat(completedFailure(store.upsert(entry())))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessage("JDBC collection is not initialized: users");
      assertThat(completedFailure(store.query(query())))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessage("JDBC collection is not initialized: users");
      assertThat(completedFailure(store.delete(new DocumentKey("users", "user-1"))))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessage("JDBC collection is not initialized: users");
      assertThat(completedFailure(store.clear("users")))
          .isInstanceOf(StorageException.Operation.class)
          .hasMessage("JDBC collection is not initialized: users");
      assertThat(dataSource.connections).isEmpty();

      assertThatNullPointerException()
          .isThrownBy(() -> store.initialize(null))
          .withMessage("documentType");
      assertThatNullPointerException().isThrownBy(() -> store.upsert(null)).withMessage("entry");
      assertThatNullPointerException().isThrownBy(() -> store.query(null)).withMessage("query");
      assertThatNullPointerException().isThrownBy(() -> store.delete(null)).withMessage("key");
      assertThatNullPointerException()
          .isThrownBy(() -> store.clear(null))
          .withMessage("collection");
      assertThatIllegalArgumentException()
          .isThrownBy(() -> store.clear(" "))
          .withMessage("collection must not be blank");
    }
  }

  @Test
  void rejectsDifferentMetadataForAnInitializedCollection() {
    RecordingDataSource dataSource = new RecordingDataSource("Test Database");
    try (JdbcExecution execution = JdbcExecution.create(1, 1)) {
      JdbcIndexStore store =
          new JdbcIndexStore(dataSource, execution, ignored -> new RecordingDialect());
      completedValue(store.initialize(User.class));

      assertThatIllegalArgumentException()
          .isThrownBy(() -> store.initialize(IncompatibleUser.class))
          .withMessage("collection is already initialized with different metadata: users");
    }
  }

  @Test
  void rejectsUnknownDatabasesBeforeSchemaMutation() {
    RecordingDataSource dataSource = new RecordingDataSource("Unknown Database");
    try (JdbcExecution execution = JdbcExecution.create(1, 1)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);

      Throwable failure = completedFailure(store.initialize(User.class));

      assertThat(failure)
          .isInstanceOf(StorageException.Operation.class)
          .hasMessage("JDBC database is not supported");
      assertThat(dataSource.connections)
          .singleElement()
          .satisfies(connection -> assertThat(connection.closed).isTrue());
    }
  }

  @Test
  void translatesSqlFailuresWithoutExposingTheirMessages() {
    RecordingDataSource dataSource = new RecordingDataSource("Test Database");
    RecordingDialect dialect = new RecordingDialect();
    try (JdbcExecution execution = JdbcExecution.create(1, 4)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution, ignored -> dialect);
      completedValue(store.initialize(User.class));

      SQLException accessCause = new SQLException("password=secret", "28000");
      dialect.sqlFailure = accessCause;
      assertTranslated(store.upsert(entry()), StorageException.Access.class, accessCause, "upsert");

      SQLException unavailableCause = new SQLException("jdbc:secret-url", "08006");
      dialect.sqlFailure = unavailableCause;
      assertTranslated(
          store.query(query()), StorageException.Unavailable.class, unavailableCause, "query");

      SQLException operationCause = new SQLException("raw SQL text", "HY000");
      dialect.sqlFailure = operationCause;
      assertTranslated(
          store.clear("users"), StorageException.Operation.class, operationCause, "clear");

      assertThat(dataSource.connections)
          .allSatisfy(connection -> assertThat(connection.closed).isTrue());
    }
  }

  @Test
  void usesTheDetectedDialectToTranslateSchemaInitializationFailures() {
    RecordingDataSource dataSource = new RecordingDataSource("Test Database");
    RecordingDialect dialect = new RecordingDialect();
    SQLException cause = new SQLException("schema lock detail", "HYT00");
    dialect.sqlFailure = cause;
    dialect.translatedFailure =
        new StorageException.Unavailable("JDBC schema initialization failed", cause);

    try (JdbcExecution execution = JdbcExecution.create(1, 1)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution, ignored -> dialect);

      assertThat(completedFailure(store.initialize(User.class)))
          .isInstanceOf(StorageException.Unavailable.class)
          .hasMessage("JDBC schema initialization failed")
          .hasCause(cause);
    }
  }

  @Test
  void convertsDialectRuntimeFailureAndExecutionShutdownToExceptionalStages() {
    RecordingDataSource dataSource = new RecordingDataSource("Test Database");
    RecordingDialect dialect = new RecordingDialect();
    JdbcExecution execution = JdbcExecution.create(1, 2);
    JdbcIndexStore store = new JdbcIndexStore(dataSource, execution, ignored -> dialect);
    completedValue(store.initialize(User.class));
    RuntimeException dialectCause = new IllegalStateException("driver implementation detail");
    dialect.runtimeFailure = dialectCause;

    Throwable dialectFailure = completedFailure(store.delete(new DocumentKey("users", "user-1")));
    assertThat(dialectFailure)
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("JDBC delete failed")
        .hasCause(dialectCause);
    assertThat(dataSource.connections)
        .allSatisfy(connection -> assertThat(connection.closed).isTrue());

    execution.close();
    Throwable shutdownFailure = completedFailure(store.clear("users"));
    assertThat(shutdownFailure)
        .isInstanceOf(StorageException.Unavailable.class)
        .hasMessage("JDBC execution is closed");
  }

  private static void assertTranslated(
      CompletionStage<?> stage,
      Class<? extends StorageException> expectedType,
      SQLException expectedCause,
      String operation) {
    Throwable failure = completedFailure(stage);
    assertThat(failure)
        .isInstanceOf(expectedType)
        .hasMessage("JDBC " + operation + " failed")
        .hasCause(expectedCause);
    assertThat(failure.getMessage()).doesNotContain(expectedCause.getMessage());
  }

  private static IndexEntry entry() {
    return new IndexEntry(
        new DocumentKey("users", "user-1"),
        Map.of(
            "country", new IndexValue.StringValue("NZ"),
            "rank", new IndexValue.LongValue(1L),
            "score", new IndexValue.DoubleValue(2.5),
            "active", new IndexValue.BooleanValue(true)));
  }

  private static IndexQuery query() {
    return new IndexQuery("users", List.of(), Optional.empty(), 10, Optional.empty());
  }

  private static <T> T completedValue(CompletionStage<T> stage) {
    var future = stage.toCompletableFuture();
    assertThat(future).succeedsWithin(Duration.ofSeconds(5));
    return future.join();
  }

  private static Throwable completedFailure(CompletionStage<?> stage) {
    try {
      stage.toCompletableFuture().join();
      throw new AssertionError("stage completed successfully");
    } catch (CompletionException failure) {
      return failure.getCause();
    }
  }

  private static final class RecordingDialect implements JdbcDatabase {

    private final List<String> operations = new ArrayList<>();
    private final List<Thread> operationThreads = new ArrayList<>();
    private CollectionMetadata metadata = JdbcMetadata.inspect(User.class);
    private IndexPage page = new IndexPage(List.of(), Optional.empty());
    private @Nullable SQLException sqlFailure;
    private @Nullable StorageException translatedFailure;
    private @Nullable RuntimeException runtimeFailure;

    @Override
    public void initialize(Connection connection, CollectionMetadata metadata) throws SQLException {
      this.metadata = metadata;
      record("initialize");
    }

    @Override
    public void clear(Connection connection, CollectionMetadata metadata) throws SQLException {
      record("clear");
    }

    @Override
    public void upsert(Connection connection, CollectionMetadata metadata, IndexEntry entry)
        throws SQLException {
      record("upsert");
    }

    @Override
    public IndexPage query(Connection connection, CollectionMetadata metadata, IndexQuery query)
        throws SQLException {
      record("query");
      return page;
    }

    @Override
    public void delete(Connection connection, CollectionMetadata metadata, DocumentKey key)
        throws SQLException {
      record("delete");
    }

    @Override
    public StorageException translate(String operation, SQLException failure) {
      StorageException translated = translatedFailure;
      return translated == null ? JdbcDatabase.super.translate(operation, failure) : translated;
    }

    private void record(String operation) throws SQLException {
      operations.add(operation);
      operationThreads.add(Thread.currentThread());
      if (sqlFailure != null) {
        SQLException failure = sqlFailure;
        sqlFailure = null;
        throw failure;
      }
      if (runtimeFailure != null) {
        RuntimeException failure = runtimeFailure;
        runtimeFailure = null;
        throw failure;
      }
    }
  }

  private static final class RecordingDataSource implements DataSource {

    private final String productName;
    private final List<RecordingConnection> connections = new ArrayList<>();

    private RecordingDataSource(String productName) {
      this.productName = productName;
    }

    @Override
    public Connection getConnection() {
      RecordingConnection connection = new RecordingConnection(productName);
      connections.add(connection);
      return connection.proxy;
    }

    @Override
    public Connection getConnection(String username, String password) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }

  private static final class RecordingConnection implements InvocationHandler {

    private final Connection proxy;
    private final DatabaseMetaData metadata;
    private boolean closed;

    private RecordingConnection(String productName) {
      metadata = proxy(DatabaseMetaData.class, new MetadataHandler(productName));
      proxy = proxy(Connection.class, this);
    }

    @Override
    public Object invoke(Object target, Method method, @Nullable Object[] arguments) {
      return switch (method.getName()) {
        case "getMetaData" -> metadata;
        case "close" -> {
          closed = true;
          yield null;
        }
        case "isClosed" -> closed;
        case "isWrapperFor" -> false;
        case "unwrap" -> throw new UnsupportedOperationException("unwrap");
        case "toString" -> "RecordingConnection";
        case "hashCode" -> System.identityHashCode(target);
        case "equals" -> target == arguments[0];
        default -> throw new UnsupportedOperationException(method.getName());
      };
    }
  }

  private record MetadataHandler(String productName) implements InvocationHandler {

    @Override
    public Object invoke(Object target, Method method, @Nullable Object[] arguments) {
      return switch (method.getName()) {
        case "getDatabaseProductName" -> productName;
        case "toString" -> "RecordingDatabaseMetaData";
        case "hashCode" -> System.identityHashCode(target);
        case "equals" -> target == arguments[0];
        default -> throw new UnsupportedOperationException(method.getName());
      };
    }
  }

  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }

  @Document("users")
  private static final class User {
    @Index private String country;
    @Index private long rank;
    @Index private double score;
    @Index private boolean active;
    @Index private java.time.Instant createdAt;
  }

  @Document("users")
  private static final class IncompatibleUser {
    @Index private Long country;
  }
}
