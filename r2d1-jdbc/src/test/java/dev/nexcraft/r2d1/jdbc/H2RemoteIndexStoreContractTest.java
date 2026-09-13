package dev.nexcraft.r2d1.jdbc;

/** Runs the complete index contract against an H2 TCP server. */
class H2RemoteIndexStoreContractTest extends AbstractRemoteJdbcIndexStoreContractTest {

  @Override
  protected TestJdbcServer.Backend backend() {
    return TestJdbcServer.Backend.H2;
  }
}
