package dev.nexcraft.r2d1.jdbc;

/** Runs the complete index contract against an HSQLDB HSQL server. */
class HsqldbRemoteIndexStoreContractTest extends AbstractRemoteJdbcIndexStoreContractTest {

  @Override
  protected TestJdbcServer.Backend backend() {
    return TestJdbcServer.Backend.HSQLDB;
  }
}
