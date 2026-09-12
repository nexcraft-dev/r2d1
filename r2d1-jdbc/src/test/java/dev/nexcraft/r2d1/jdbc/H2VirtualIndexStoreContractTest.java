package dev.nexcraft.r2d1.jdbc;

/** Runs the reusable H2 contract with the explicit virtual-thread execution mode. */
class H2VirtualIndexStoreContractTest extends H2IndexStoreContractTest {

  @Override
  protected JdbcExecution createExecution() {
    return createVirtualExecution();
  }
}
