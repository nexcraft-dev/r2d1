package dev.nexcraft.r2d1.jdbc;

/** Runs the complete H2 JDBC lifecycle suite with explicit virtual-thread execution. */
class H2VirtualIndexStoreTest extends H2IndexStoreTest {

  @Override
  protected JdbcExecution createExecution(int maxConcurrency, int maxPending) {
    return createVirtualExecution(maxConcurrency, maxPending);
  }
}
