package dev.nexcraft.r2d1.jdbc;

/** Runs the complete HSQLDB JDBC lifecycle suite with explicit virtual-thread execution. */
class HsqldbVirtualIndexStoreTest extends HsqldbIndexStoreTest {

  @Override
  protected JdbcExecution createExecution(int maxConcurrency, int maxPending) {
    return createVirtualExecution(maxConcurrency, maxPending);
  }
}
