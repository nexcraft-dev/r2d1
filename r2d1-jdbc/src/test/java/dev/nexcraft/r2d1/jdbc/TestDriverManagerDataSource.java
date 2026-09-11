package dev.nexcraft.r2d1.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** Minimal driver-neutral data source for file-backed JDBC integration tests. */
final class TestDriverManagerDataSource implements DataSource {

  private final String url;
  private final String username;
  private final String password;
  private volatile @Nullable SQLException connectionFailure;
  private final AtomicInteger activeConnections = new AtomicInteger();
  private final AtomicInteger peakConnections = new AtomicInteger();
  private volatile boolean connectionTracking;
  private @Nullable PrintWriter logWriter;
  private int loginTimeout;

  TestDriverManagerDataSource(String url) {
    this(url, "sa", "");
  }

  TestDriverManagerDataSource(String url, String username, String password) {
    this.url = Objects.requireNonNull(url, "url");
    this.username = Objects.requireNonNull(username, "username");
    this.password = Objects.requireNonNull(password, "password");
  }

  void failConnections(SQLException failure) {
    connectionFailure = Objects.requireNonNull(failure, "failure");
  }

  void clearConnectionFailure() {
    connectionFailure = null;
  }

  void trackConnections() {
    connectionTracking = true;
  }

  int peakConnectionCount() {
    return peakConnections.get();
  }

  @Override
  public Connection getConnection() throws SQLException {
    SQLException failure = connectionFailure;
    if (failure != null) {
      throw failure;
    }
    return track(DriverManager.getConnection(url, username, password));
  }

  @Override
  public Connection getConnection(String suppliedUsername, String suppliedPassword)
      throws SQLException {
    SQLException failure = connectionFailure;
    if (failure != null) {
      throw failure;
    }
    return track(DriverManager.getConnection(url, suppliedUsername, suppliedPassword));
  }

  @Override
  public @Nullable PrintWriter getLogWriter() {
    return logWriter;
  }

  @Override
  public void setLogWriter(@Nullable PrintWriter out) {
    logWriter = out;
  }

  @Override
  public void setLoginTimeout(int seconds) {
    loginTimeout = seconds;
  }

  @Override
  public int getLoginTimeout() {
    return loginTimeout;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return Logger.getLogger("dev.nexcraft.r2d1.jdbc.test");
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("not a wrapper");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  private Connection track(Connection connection) {
    if (!connectionTracking) {
      return connection;
    }
    int active = activeConnections.incrementAndGet();
    peakConnections.accumulateAndGet(active, Math::max);
    AtomicBoolean closed = new AtomicBoolean();
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              try {
                return method.invoke(connection, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              } finally {
                if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) {
                  activeConnections.decrementAndGet();
                }
              }
            });
  }
}
