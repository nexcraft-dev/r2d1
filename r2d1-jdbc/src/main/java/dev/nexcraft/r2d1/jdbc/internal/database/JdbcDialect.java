package dev.nexcraft.r2d1.jdbc.internal.database;

import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.SQLException;

/**
 * Database-specific JDBC behavior kept internal until concrete dialects stabilize the contract.
 *
 * <p>The store owns the connection scope. Implementations must not close the supplied connection
 * and must close every statement and result set they create on both success and failure paths.
 */
interface JdbcDialect extends JdbcDatabase {

  @Override
  public default StorageException translate(String operation, SQLException failure) {
    return JdbcExceptions.translate(operation, failure);
  }
}
