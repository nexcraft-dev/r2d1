package dev.nexcraft.r2d1.jdbc;

/** Runs the complete SQLite JDBC lifecycle suite with explicit virtual-thread execution. */
class SqliteVirtualIndexStoreTest extends SqliteIndexStoreTest {

  @Override
  protected JdbcExecution createExecution(int maxConcurrency, int maxPending) {
    return createVirtualExecution(maxConcurrency, maxPending);
  }
}
