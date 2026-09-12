package dev.nexcraft.r2d1.jdbc;

import java.nio.file.Path;

/** Runs the common asynchronous index contract against a persistent SQLite file database. */
class SqliteIndexStoreContractTest extends AbstractJdbcIndexStoreContractTest {

  @Override
  protected String fileUrl(Path databasePath) {
    return "jdbc:sqlite:"
        + databasePath.resolveSibling(databasePath.getFileName() + ".db").toAbsolutePath();
  }
}
