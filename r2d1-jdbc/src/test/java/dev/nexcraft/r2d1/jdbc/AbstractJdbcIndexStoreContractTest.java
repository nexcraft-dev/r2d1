package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spi.testing.IndexStoreContractTest;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;

/** Shared persistent JDBC fixture for the reusable {@link IndexStore} contract. */
abstract class AbstractJdbcIndexStoreContractTest extends IndexStoreContractTest {

  @TempDir Path temporaryDirectory;

  protected abstract String fileUrl(Path databasePath);

  protected void shutdown(DataSource dataSource) throws SQLException {}

  /** Creates the execution resource used by the contract fixture. */
  protected JdbcExecution createExecution() {
    return JdbcExecution.create(2, 32);
  }

  /** Creates a Java 25 virtual-thread execution resource for mode-parity fixtures. */
  protected final JdbcExecution createVirtualExecution() {
    return JdbcExecution.create(new JdbcExecutionConfig(JdbcExecutionMode.VIRTUAL_THREAD, 2, 32));
  }

  @Override
  protected final Adapter createAdapter() {
    TestDriverManagerDataSource dataSource =
        new TestDriverManagerDataSource(fileUrl(temporaryDirectory.resolve("contract")));
    JdbcExecution execution = createExecution();
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
}
