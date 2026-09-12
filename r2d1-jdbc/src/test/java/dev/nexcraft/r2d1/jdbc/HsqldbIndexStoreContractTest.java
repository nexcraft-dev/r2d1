package dev.nexcraft.r2d1.jdbc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** Runs the common asynchronous index contract against a persistent HSQLDB file database. */
class HsqldbIndexStoreContractTest extends AbstractJdbcIndexStoreContractTest {

  @Override
  protected String fileUrl(Path databasePath) {
    return "jdbc:hsqldb:file:" + databasePath.toAbsolutePath();
  }

  @Override
  protected void shutdown(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    }
  }
}
