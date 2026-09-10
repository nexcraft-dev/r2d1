package dev.nexcraft.r2d1.jdbc;

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

  private static boolean hasStateClass(SQLException failure, String expected) {
    String state = failure.getSQLState();
    return state != null && state.startsWith(expected);
  }
}
