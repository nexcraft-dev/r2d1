package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** H2-specific lifecycle and failure classification coverage. */
class H2IndexStoreTest extends JdbcBackendTest {

  private static final String VARCHAR = "CHARACTER VARYING(1000000000)";

  @Override
  protected String fileUrl(Path databasePath) {
    return "jdbc:h2:file:" + databasePath.toAbsolutePath();
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
    assertThat(Files.exists(databasePath.resolveSibling("persistent.mv.db"))).isTrue();
  }

  @Override
  protected void shutdown(DataSource dataSource) {}

  @Test
  void translatesRealH2LockTimeoutsAsUnavailableWithoutLeakingDetails() throws SQLException {
    // This test-only option makes the row-lock failure deterministic and does not alter lifecycle.
    TestDriverManagerDataSource dataSource =
        new TestDriverManagerDataSource(
            fileUrl(temporaryDirectory.resolve("locked")) + ";LOCK_TIMEOUT=50");
    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      JdbcIndexStore store = new JdbcIndexStore(dataSource, execution);
      completedValue(store.initialize(LockDocument.class));
      completedValue(
          store.upsert(
              entry("lock_entries", "locked", Map.of("rank", new IndexValue.LongValue(1L)))));

      try (Connection blocker = dataSource.getConnection();
          PreparedStatement statement =
              blocker.prepareStatement(
                  "UPDATE \"lock_entries\" SET \"rank\" = ? WHERE \"document_id\" = ?")) {
        blocker.setAutoCommit(false);
        statement.setLong(1, 2L);
        statement.setString(2, "locked");
        assertThat(statement.executeUpdate()).isEqualTo(1);

        Throwable failure =
            completedFailure(
                store.upsert(
                    entry("lock_entries", "locked", Map.of("rank", new IndexValue.LongValue(3L)))));
        assertThat(failure)
            .isInstanceOf(StorageException.Unavailable.class)
            .hasMessage("JDBC upsert failed")
            .hasCauseInstanceOf(SQLException.class);
        assertThat(failure.getMessage()).doesNotContain("locked", "jdbc:h2");
        blocker.rollback();
      }
    }
  }

  @Test
  void translatesRealH2AuthenticationFailuresAsAccessWithoutLeakingCredentials() {
    String url = fileUrl(temporaryDirectory.resolve("authentication"));
    TestDriverManagerDataSource authorized =
        new TestDriverManagerDataSource(url, "sa", "correct-password");
    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      completedValue(
          new JdbcIndexStore(authorized, execution).initialize(AuthenticationDocument.class));
    }

    TestDriverManagerDataSource unauthorized =
        new TestDriverManagerDataSource(url, "sa", "wrong-password");
    try (JdbcExecution execution = JdbcExecution.create(1, 8)) {
      Throwable failure =
          completedFailure(
              new JdbcIndexStore(unauthorized, execution).initialize(AuthenticationDocument.class));
      assertThat(failure)
          .isInstanceOf(StorageException.Access.class)
          .hasMessage("JDBC schema initialization failed")
          .hasCauseInstanceOf(SQLException.class);
      assertThat(failure.getMessage()).doesNotContain("password", "jdbc:h2");
    }
  }

  @Document("lock_entries")
  private static final class LockDocument {
    @Index private long rank;
  }

  @Document("authentication_entries")
  private static final class AuthenticationDocument {
    @Index private String country;
  }
}
