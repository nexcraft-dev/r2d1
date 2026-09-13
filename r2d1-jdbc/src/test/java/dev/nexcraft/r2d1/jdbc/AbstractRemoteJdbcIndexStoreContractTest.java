package dev.nexcraft.r2d1.jdbc;

import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** Runs the reusable index contract against an in-process remote JDBC server. */
abstract class AbstractRemoteJdbcIndexStoreContractTest extends AbstractJdbcIndexStoreContractTest {

  private @Nullable TestJdbcServer server;

  protected abstract TestJdbcServer.Backend backend();

  @Override
  protected final String fileUrl(Path databasePath) {
    try {
      server = TestJdbcServer.start(backend(), databasePath.getParent());
      return server.jdbcUrl();
    } catch (SQLException failure) {
      throw new IllegalStateException("could not start remote JDBC test server", failure);
    }
  }

  @Override
  protected final void shutdown(DataSource dataSource) {
    TestJdbcServer current = server;
    server = null;
    if (current != null) {
      current.close();
    }
  }
}
