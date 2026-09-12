package dev.nexcraft.r2d1.jdbc;

/** Runs the reusable SQLite contract with the explicit virtual-thread execution mode. */
class SqliteVirtualIndexStoreContractTest extends SqliteIndexStoreContractTest {

  @Override
  protected JdbcExecution createExecution() {
    return createVirtualExecution();
  }
}
