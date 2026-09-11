package dev.nexcraft.r2d1.jdbc.internal.database.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.jdbc.internal.database.JdbcWriteCoordinator;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqliteDialectTest {

  private final SqliteDialect dialect = new SqliteDialect(JdbcWriteCoordinator.direct());

  @Test
  void translatesPrimaryAndExtendedBusyAndLockedCodesAsUnavailable() {
    assertTranslation(5, StorageException.Unavailable.class);
    assertTranslation(6, StorageException.Unavailable.class);
    assertTranslation(261, StorageException.Unavailable.class);
    assertTranslation(262, StorageException.Unavailable.class);
  }

  @Test
  void translatesPermissionCodesAsAccessAndOtherCodesAsOperation() {
    assertTranslation(3, StorageException.Access.class);
    assertTranslation(8, StorageException.Access.class);
    assertTranslation(23, StorageException.Access.class);
    assertTranslation(1, StorageException.Operation.class);
  }

  private void assertTranslation(int errorCode, Class<? extends StorageException> expectedType) {
    SQLException cause = new SQLException("native SQLite detail", null, errorCode);
    StorageException translated = dialect.translate("test operation", cause);
    assertThat(translated)
        .isInstanceOf(expectedType)
        .hasMessage("JDBC test operation failed")
        .hasCause(cause);
  }
}
