package dev.nexcraft.r2d1.jdbc;

/** Verifies shared-endpoint deployment behavior against an H2 TCP server. */
class H2RemoteDeploymentTest extends AbstractRemoteJdbcDeploymentTest {

  @Override
  protected TestJdbcServer.Backend backend() {
    return TestJdbcServer.Backend.H2;
  }
}
