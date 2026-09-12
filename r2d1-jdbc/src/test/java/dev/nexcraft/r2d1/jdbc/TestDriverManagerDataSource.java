package dev.nexcraft.r2d1.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
  private final AtomicInteger activeStatements = new AtomicInteger();
  private final AtomicInteger peakStatements = new AtomicInteger();
  private final AtomicInteger activeResultSets = new AtomicInteger();
  private final AtomicInteger peakResultSets = new AtomicInteger();
  private final AtomicInteger activeMetadataResultSets = new AtomicInteger();
  private final AtomicInteger peakMetadataResultSets = new AtomicInteger();
  private final AtomicInteger ddlStatements = new AtomicInteger();
  private final AtomicInteger activeMutations = new AtomicInteger();
  private final AtomicInteger peakMutations = new AtomicInteger();
  private final AtomicReference<@Nullable SQLException> statementFailure = new AtomicReference<>();
  private final AtomicReference<@Nullable CountDownLatch> expectedConnections =
      new AtomicReference<>();
  private final AtomicReference<@Nullable MutationGate> mutationGate = new AtomicReference<>();
  private volatile boolean resourceTracking;
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

  void trackResources() {
    resourceTracking = true;
  }

  ResourceSnapshot resourceSnapshot() {
    return new ResourceSnapshot(
        activeConnections.get(),
        peakConnections.get(),
        activeStatements.get(),
        peakStatements.get(),
        activeResultSets.get(),
        peakResultSets.get(),
        activeMetadataResultSets.get(),
        peakMetadataResultSets.get(),
        ddlStatements.get());
  }

  void resetDdlStatementCount() {
    ddlStatements.set(0);
  }

  void failNextStatementExecution(SQLException failure) {
    statementFailure.set(Objects.requireNonNull(failure, "failure"));
  }

  CountDownLatch expectConnectionAcquisitions(int count) {
    CountDownLatch expectation = new CountDownLatch(count);
    expectedConnections.set(expectation);
    return expectation;
  }

  MutationGate blockNextMutation() {
    peakMutations.set(0);
    MutationGate gate = new MutationGate(new CountDownLatch(1), new CountDownLatch(1));
    mutationGate.set(gate);
    return gate;
  }

  int peakConcurrentMutations() {
    return peakMutations.get();
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
    if (!resourceTracking) {
      return connection;
    }
    increment(activeConnections, peakConnections);
    CountDownLatch connectionExpectation = expectedConnections.get();
    if (connectionExpectation != null) {
      connectionExpectation.countDown();
      if (connectionExpectation.getCount() == 0) {
        expectedConnections.compareAndSet(connectionExpectation, null);
      }
    }
    AtomicBoolean closed = new AtomicBoolean();
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              try {
                Object result = method.invoke(connection, arguments);
                if (result instanceof PreparedStatement statement) {
                  String sql =
                      arguments != null
                              && arguments.length > 0
                              && arguments[0] instanceof String text
                          ? text
                          : null;
                  return trackStatement(statement, sql, PreparedStatement.class);
                }
                if (result instanceof Statement statement) {
                  return trackStatement(statement, null, Statement.class);
                }
                if (result instanceof DatabaseMetaData metadata) {
                  return trackMetadata(metadata);
                }
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              } finally {
                if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) {
                  activeConnections.decrementAndGet();
                }
              }
            });
  }

  private Statement trackStatement(
      Statement statement, @Nullable String preparedSql, Class<? extends Statement> type) {
    increment(activeStatements, peakStatements);
    AtomicBoolean closed = new AtomicBoolean();
    return (Statement)
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, arguments) -> {
              try {
                if (method.getName().startsWith("execute")) {
                  SQLException failure = statementFailure.getAndSet(null);
                  if (failure != null) {
                    throw failure;
                  }
                  String sql = preparedSql;
                  if (arguments != null
                      && arguments.length > 0
                      && arguments[0] instanceof String text) {
                    sql = text;
                  }
                  if (isDdl(sql)) {
                    ddlStatements.incrementAndGet();
                  }
                  if (isMutation(sql)) {
                    increment(activeMutations, peakMutations);
                    try {
                      MutationGate gate = mutationGate.getAndSet(null);
                      if (gate != null) {
                        gate.started().countDown();
                        try {
                          gate.release().await();
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new SQLException("test mutation gate was interrupted", interrupted);
                        }
                      }
                      Object result = method.invoke(statement, arguments);
                      return result instanceof ResultSet resultSet
                          ? trackResultSet(resultSet, false)
                          : result;
                    } finally {
                      activeMutations.decrementAndGet();
                    }
                  }
                }
                Object result = method.invoke(statement, arguments);
                return result instanceof ResultSet resultSet
                    ? trackResultSet(resultSet, false)
                    : result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              } finally {
                if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) {
                  activeStatements.decrementAndGet();
                }
              }
            });
  }

  private DatabaseMetaData trackMetadata(DatabaseMetaData metadata) {
    return (DatabaseMetaData)
        Proxy.newProxyInstance(
            DatabaseMetaData.class.getClassLoader(),
            new Class<?>[] {DatabaseMetaData.class},
            (proxy, method, arguments) -> {
              try {
                Object result = method.invoke(metadata, arguments);
                return result instanceof ResultSet resultSet
                    ? trackResultSet(resultSet, true)
                    : result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private ResultSet trackResultSet(ResultSet resultSet, boolean metadata) {
    increment(activeResultSets, peakResultSets);
    if (metadata) {
      increment(activeMetadataResultSets, peakMetadataResultSets);
    }
    AtomicBoolean closed = new AtomicBoolean();
    return (ResultSet)
        Proxy.newProxyInstance(
            ResultSet.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, arguments) -> {
              try {
                return method.invoke(resultSet, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              } finally {
                if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) {
                  activeResultSets.decrementAndGet();
                  if (metadata) {
                    activeMetadataResultSets.decrementAndGet();
                  }
                }
              }
            });
  }

  private static void increment(AtomicInteger activeCounter, AtomicInteger peakCounter) {
    int active = activeCounter.incrementAndGet();
    peakCounter.accumulateAndGet(active, Math::max);
  }

  private static boolean isDdl(@Nullable String sql) {
    if (sql == null) {
      return false;
    }
    String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
    return normalized.startsWith("CREATE ")
        || normalized.startsWith("ALTER ")
        || normalized.startsWith("DROP ");
  }

  private static boolean isMutation(@Nullable String sql) {
    if (sql == null) {
      return false;
    }
    String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
    return normalized.startsWith("INSERT ")
        || normalized.startsWith("MERGE ")
        || normalized.startsWith("UPDATE ")
        || normalized.startsWith("DELETE ");
  }

  record ResourceSnapshot(
      int activeConnections,
      int peakConnections,
      int activeStatements,
      int peakStatements,
      int activeResultSets,
      int peakResultSets,
      int activeMetadataResultSets,
      int peakMetadataResultSets,
      int ddlStatements) {}

  record MutationGate(CountDownLatch started, CountDownLatch release) {}
}
