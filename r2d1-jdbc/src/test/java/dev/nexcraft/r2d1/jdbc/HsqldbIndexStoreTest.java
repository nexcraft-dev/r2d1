package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** HSQLDB-specific schema, lifecycle, parameter, and failure coverage. */
class HsqldbIndexStoreTest extends JdbcBackendTest {

  private static final String VARCHAR = "VARCHAR(1000000000)";
  private static final Duration HOLDER_READY_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration HOLDER_EXIT_TIMEOUT = Duration.ofSeconds(5);

  @Override
  protected String fileUrl(Path databasePath) {
    return "jdbc:hsqldb:file:" + databasePath.toAbsolutePath();
  }

  @Override
  protected String stringColumnType() {
    return VARCHAR;
  }

  @Override
  protected String longColumnType() {
    return "BIGINT";
  }

  @Override
  protected void assertDatabaseFiles(Path databasePath) {
    assertThat(Files.exists(databasePath.resolveSibling("persistent.properties"))).isTrue();
    assertThat(Files.exists(databasePath.resolveSibling("persistent.script"))).isTrue();
  }

  @Override
  protected String reopenUrl(String url) {
    return url + ";ifexists=true";
  }

  @Override
  protected void shutdown(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    }
  }

  @Test
  void supportsDurableZeroIndexMergeAndIdentityOperations() throws SQLException {
    Path databasePath = temporaryDirectory.resolve("zero_index");
    String url = fileUrl(databasePath);
    TestDriverManagerDataSource dataSource = new TestDriverManagerDataSource(url);
    DocumentKey key = new DocumentKey("zero_index_entries", "first");

    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(ZeroIndexDocument.class));
      IndexEntry entry = new IndexEntry(key, Map.of());
      completedValue(store.upsert(entry));
      completedValue(store.upsert(entry));
      assertThat(completedValue(store.query(unsortedQuery("zero_index_entries"))).documentKeys())
          .containsExactly(key);
    }
    shutdown(dataSource);

    TestDriverManagerDataSource reopenedDataSource =
        new TestDriverManagerDataSource(reopenUrl(url));
    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      JdbcIndexStore reopened = new JdbcIndexStore(reopenedDataSource, execution);
      completedValue(reopened.initialize(ZeroIndexDocument.class));
      IndexPage page = completedValue(reopened.query(unsortedQuery("zero_index_entries")));
      assertThat(page.documentKeys()).containsExactly(key);
      completedValue(reopened.delete(key));
      assertThat(completedValue(reopened.query(unsortedQuery("zero_index_entries"))).documentKeys())
          .isEmpty();
      completedValue(reopened.upsert(new IndexEntry(key, Map.of())));
      completedValue(reopened.clear("zero_index_entries"));
      assertThat(completedValue(reopened.query(unsortedQuery("zero_index_entries"))).documentKeys())
          .isEmpty();
    }
    shutdown(reopenedDataSource);
  }

  @Test
  void translatesAuthenticationFailuresAsAccessWithoutLeakingCredentials() throws SQLException {
    String url = fileUrl(temporaryDirectory.resolve("authentication"));
    TestDriverManagerDataSource authorized =
        new TestDriverManagerDataSource(url, "sa", "correct-password");
    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      completedValue(
          new JdbcIndexStore(authorized, execution).initialize(AuthenticationDocument.class));
    }
    shutdown(authorized);

    TestDriverManagerDataSource unauthorized =
        new TestDriverManagerDataSource(reopenUrl(url), "sa", "wrong-password");
    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      Throwable failure =
          completedFailure(
              new JdbcIndexStore(unauthorized, execution).initialize(AuthenticationDocument.class));
      assertThat(failure)
          .isInstanceOf(StorageException.Access.class)
          .hasMessage("JDBC schema initialization failed")
          .hasCauseInstanceOf(SQLException.class);
      assertThat(failure.getMessage()).doesNotContain("password", "jdbc:hsqldb");
      assertThat(failure.getCause()).hasFieldOrPropertyWithValue("SQLState", "28000");
    }
  }

  @Test
  void translatesInitialRealFileLocksAsUnavailableWithoutLeakingDetails()
      throws IOException, InterruptedException {
    Path databasePath = temporaryDirectory.resolve("initially_locked");
    String url = fileUrl(databasePath);
    TestDriverManagerDataSource dataSource = new TestDriverManagerDataSource(url);
    JdbcExecution execution = JdbcExecution.create(1, 8);
    LockHolder holder = null;
    try {
      holder = startLockHolder(url);
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      Throwable failure =
          completedFailure(store.initialize(LockDocument.class), Duration.ofSeconds(30));
      assertThat(failure)
          .isInstanceOf(StorageException.Unavailable.class)
          .hasMessage("JDBC schema initialization failed")
          .hasCauseInstanceOf(SQLException.class);
      SQLException cause = (SQLException) failure.getCause();
      assertThat(cause.getSQLState()).isEqualTo("S1000");
      assertThat(cause.getErrorCode()).isEqualTo(-451);
      assertThat(failure.getMessage())
          .doesNotContain("jdbc:hsqldb", databasePath.toString(), "initially_locked");
    } finally {
      execution.close();
      releaseLockHolder(holder);
    }
  }

  @Test
  void translatesRealFileLocksAsUnavailableWithoutLeakingDetails()
      throws IOException, InterruptedException, SQLException {
    Path databasePath = temporaryDirectory.resolve("locked");
    String url = fileUrl(databasePath);
    TestDriverManagerDataSource dataSource = new TestDriverManagerDataSource(url);
    JdbcExecution execution = JdbcExecution.create(1, 8);
    LockHolder holder = null;
    try {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(LockDocument.class));
      completedValue(
          store.upsert(
              entry("lock_entries", "locked", Map.of("rank", new IndexValue.LongValue(1L)))));
      shutdown(dataSource);

      holder = startLockHolder(url);
      Throwable failure =
          completedFailure(
              store.upsert(
                  entry("lock_entries", "locked", Map.of("rank", new IndexValue.LongValue(2L)))),
              Duration.ofSeconds(30));
      assertThat(failure)
          .isInstanceOf(StorageException.Unavailable.class)
          .hasMessage("JDBC upsert failed")
          .hasCauseInstanceOf(SQLException.class);
      SQLException cause = (SQLException) failure.getCause();
      assertThat(cause.getSQLState()).isEqualTo("S1000");
      assertThat(cause.getErrorCode()).isEqualTo(-451);
      assertThat(failure.getMessage())
          .doesNotContain("jdbc:hsqldb", databasePath.toString(), "locked");
    } finally {
      execution.close();
      releaseLockHolder(holder);
    }
  }

  private static LockHolder startLockHolder(String url) throws IOException, InterruptedException {
    String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process process =
        new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                HsqldbLockHolder.class.getName(),
                url)
            .redirectErrorStream(true)
            .start();
    LockHolder holder = new LockHolder(process);
    try {
      holder.awaitReady();
      return holder;
    } catch (IOException | InterruptedException | RuntimeException failure) {
      try {
        if (holder.forceCleanup()) {
          failure.addSuppressed(new AssertionError("lock holder required forced termination"));
        }
      } catch (IOException | InterruptedException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private static void releaseLockHolder(LockHolder holder)
      throws IOException, InterruptedException {
    if (holder != null) {
      holder.release();
    }
  }

  @Document("authentication_entries")
  private static final class AuthenticationDocument {
    @Index private String country;
  }

  @Document("lock_entries")
  private static final class LockDocument {
    @Index private long rank;
  }

  @Document("zero_index_entries")
  private static final class ZeroIndexDocument {
    private String ignored;
  }

  private static final class LockHolder {

    private final Process process;
    private final InputStream output;
    private final OutputStream input;

    private LockHolder(Process process) {
      this.process = process;
      output = process.getInputStream();
      input = process.getOutputStream();
    }

    private void awaitReady() throws IOException, InterruptedException {
      ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
      Future<String> readiness =
          readerExecutor.submit(
              () ->
                  new BufferedReader(new InputStreamReader(output, StandardCharsets.UTF_8))
                      .readLine());
      try {
        String line = readiness.get(HOLDER_READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (line == null) {
          throw new IOException("HSQLDB lock holder output closed before readiness");
        }
        if (!"READY".equals(line.strip())) {
          throw new IOException("HSQLDB lock holder returned unexpected readiness: " + line);
        }
      } catch (ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof IOException ioFailure) {
          throw ioFailure;
        }
        throw new IOException("HSQLDB lock holder readiness failed", cause);
      } catch (TimeoutException failure) {
        readiness.cancel(true);
        throw new IOException("HSQLDB lock holder readiness timed out", failure);
      } finally {
        readerExecutor.shutdownNow();
      }
    }

    private void release() throws IOException, InterruptedException {
      try {
        input.write('\n');
        input.flush();
        input.close();
        if (!process.waitFor(
            HOLDER_EXIT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor(
              HOLDER_EXIT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
          throw new AssertionError("HSQLDB lock holder required forced termination");
        }
        assertThat(process.exitValue()).as("HSQLDB lock holder exit code").isZero();
      } finally {
        closeStreams();
      }
    }

    private boolean forceCleanup() throws IOException, InterruptedException {
      boolean forced = process.isAlive();
      try {
        if (forced) {
          process.destroyForcibly();
          if (!process.waitFor(
              HOLDER_EXIT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw new IOException("HSQLDB lock holder did not terminate after forced cleanup");
          }
        }
        return forced;
      } finally {
        closeStreams();
      }
    }

    private void closeStreams() throws IOException {
      IOException failure = null;
      try {
        output.close();
      } catch (IOException closeFailure) {
        failure = closeFailure;
      }
      try {
        input.close();
      } catch (IOException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }
}
