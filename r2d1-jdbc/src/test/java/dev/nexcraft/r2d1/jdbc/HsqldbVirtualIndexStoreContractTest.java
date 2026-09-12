package dev.nexcraft.r2d1.jdbc;

/** Runs the reusable HSQLDB contract with the explicit virtual-thread execution mode. */
class HsqldbVirtualIndexStoreContractTest extends HsqldbIndexStoreContractTest {

  @Override
  protected JdbcExecution createExecution() {
    return createVirtualExecution();
  }
}
