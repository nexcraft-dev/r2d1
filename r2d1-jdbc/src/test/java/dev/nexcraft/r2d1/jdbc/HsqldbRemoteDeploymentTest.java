package dev.nexcraft.r2d1.jdbc;

/** Verifies shared-endpoint deployment behavior against an HSQLDB HSQL server. */
class HsqldbRemoteDeploymentTest extends AbstractRemoteJdbcDeploymentTest {

  @Override
  protected TestJdbcServer.Backend backend() {
    return TestJdbcServer.Backend.HSQLDB;
  }
}
