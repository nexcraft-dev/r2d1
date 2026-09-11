package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spi.testing.IndexStoreContractTest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;

/** Runs the common asynchronous index contract against a persistent HSQLDB file database. */
class HsqldbIndexStoreContractTest extends IndexStoreContractTest {

  @TempDir Path temporaryDirectory;

  @Override
  protected Adapter createAdapter() {
    String url = "jdbc:hsqldb:file:" + temporaryDirectory.resolve("contract").toAbsolutePath();
    TestDriverManagerDataSource dataSource = new TestDriverManagerDataSource(url);
    JdbcExecution execution = JdbcExecution.create(2, 32);
    JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
    return new Adapter() {
      @Override
      public IndexStore store() {
        return store;
      }

      @Override
      public CompletionStage<@Nullable Void> initialize(Class<?> documentType) {
        return store.initialize(documentType);
      }

      @Override
      public CompletionStage<?> triggerStorageFailure() {
        dataSource.failConnections(new SQLException("native test failure", "08006"));
        return store.query(
            new IndexQuery(COLLECTION, List.of(), Optional.empty(), 1, Optional.empty()));
      }

      @Override
      public void close() throws SQLException {
        execution.close();
        dataSource.clearConnectionFailure();
        shutdown(dataSource);
      }
    };
  }

  private static void shutdown(TestDriverManagerDataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    }
  }
}
