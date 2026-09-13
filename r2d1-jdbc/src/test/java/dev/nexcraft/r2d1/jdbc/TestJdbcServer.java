package dev.nexcraft.r2d1.jdbc;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import org.hsqldb.server.ServerConstants;
import org.jspecify.annotations.Nullable;

/** In-process, loopback-only JDBC server fixture backed by a temporary file database. */
final class TestJdbcServer {

  enum Backend {
    H2,
    HSQLDB
  }

  private final Backend backend;
  private final Path temporaryDirectory;
  private final String alias;
  private int port;
  private org.h2.tools.@Nullable Server h2Server;
  private org.hsqldb.@Nullable Server hsqldbServer;

  private TestJdbcServer(Backend backend, Path temporaryDirectory) {
    this.backend = Objects.requireNonNull(backend, "backend");
    this.temporaryDirectory =
        Objects.requireNonNull(temporaryDirectory, "temporaryDirectory").toAbsolutePath();
    this.alias = "r2d1_" + UUID.randomUUID().toString().replace("-", "");
  }

  static TestJdbcServer start(Backend backend, Path temporaryDirectory) throws SQLException {
    TestJdbcServer server = new TestJdbcServer(backend, temporaryDirectory);
    server.start();
    return server;
  }

  String jdbcUrl() {
    if (port == 0 || (h2Server == null && hsqldbServer == null)) {
      throw new IllegalStateException("test JDBC server is not running");
    }
    return switch (backend) {
      case H2 -> "jdbc:h2:tcp://127.0.0.1:" + port + "/./" + alias;
      case HSQLDB -> "jdbc:hsqldb:hsql://127.0.0.1:" + port + "/" + alias;
    };
  }

  void restart() throws SQLException {
    if (h2Server != null || hsqldbServer != null) {
      throw new IllegalStateException("test JDBC server is already running");
    }
    start();
  }

  void stop() {
    org.h2.tools.Server currentH2 = h2Server;
    h2Server = null;
    if (currentH2 != null) {
      currentH2.stop();
    }
    org.hsqldb.Server currentHsqldb = hsqldbServer;
    hsqldbServer = null;
    if (currentHsqldb != null) {
      currentHsqldb.shutdown();
    }
  }

  void close() {
    stop();
  }

  private void start() throws SQLException {
    switch (backend) {
      case H2 -> startH2();
      case HSQLDB -> startHsqldb();
    }
  }

  private void startH2() throws SQLException {
    org.h2.tools.Server server =
        org.h2.tools.Server.createTcpServer(
                "-tcpPort",
                Integer.toString(port),
                "-tcpDaemon",
                "-baseDir",
                temporaryDirectory.toString(),
                "-ifNotExists")
            .start();
    if (!server.isRunning(true)) {
      server.stop();
      throw new SQLException("H2 test server did not become ready");
    }
    port = server.getPort();
    h2Server = server;
  }

  private void startHsqldb() throws SQLException {
    org.hsqldb.Server server = new org.hsqldb.Server();
    server.setNoSystemExit(true);
    server.setSilent(true);
    server.setDaemon(true);
    server.setAddress("127.0.0.1");
    server.setPort(port);
    server.setDatabaseName(0, alias);
    server.setDatabasePath(0, "file:" + temporaryDirectory.resolve(alias));
    server.start();
    int state = server.getState();
    if (state != ServerConstants.SERVER_STATE_ONLINE) {
      server.shutdown();
      throw new SQLException("HSQLDB test server did not become ready: " + state);
    }
    port = server.getLocalPort();
    hsqldbServer = server;
  }
}
