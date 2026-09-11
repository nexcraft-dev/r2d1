package dev.nexcraft.r2d1.jdbc.internal.database;

import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Objects;

/** Applies conservative, vendor-neutral translation at the JDBC boundary. */
final class JdbcExceptions {

  private JdbcExceptions() {}

  static StorageException translate(String operation, SQLException failure) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(failure, "failure");
    String message = "JDBC " + operation + " failed";
    if (failure instanceof SQLInvalidAuthorizationSpecException || hasStateClass(failure, "28")) {
      return new StorageException.Access(message, failure);
    }
    if (failure instanceof SQLTransientConnectionException
        || failure instanceof SQLNonTransientConnectionException
        || failure instanceof SQLRecoverableException
        || hasStateClass(failure, "08")) {
      return new StorageException.Unavailable(message, failure);
    }
    return new StorageException.Operation(message, failure);
  }

  /**
   * Translates a connection failure raised before database metadata can identify a dialect.
   *
   * <p>Only the verified HSQLDB embedded-file lock signature is classified specially here. All
   * other failures use the conservative vendor-neutral mapping.
   */
  static StorageException translateBeforeDetection(String operation, SQLException failure) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(failure, "failure");
    if (isHsqldbFileLock(failure)) {
      return new StorageException.Unavailable("JDBC " + operation + " failed", failure);
    }
    return translate(operation, failure);
  }

  private static boolean isHsqldbFileLock(SQLException failure) {
    return failure.getErrorCode() == -451 && "S1000".equals(failure.getSQLState());
  }

  private static boolean hasStateClass(SQLException failure, String expected) {
    String state = failure.getSQLState();
    return state != null && state.startsWith(expected);
  }
}
