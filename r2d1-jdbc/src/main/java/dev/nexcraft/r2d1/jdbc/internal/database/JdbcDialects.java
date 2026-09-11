package dev.nexcraft.r2d1.jdbc.internal.database;

import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Objects;

/** Resolves built-in JDBC dialects before any database schema mutation occurs. */
public final class JdbcDialects {

  private JdbcDialects() {}

  public static JdbcDatabase detect(DatabaseMetaData metadata) throws SQLException {
    String productName = Objects.requireNonNull(metadata, "metadata").getDatabaseProductName();
    if ("H2".equals(productName)) {
      return new H2Dialect();
    }
    if ("HSQL Database Engine".equals(productName)) {
      return new HsqldbDialect();
    }
    throw new StorageException.Operation("JDBC database is not supported");
  }

  /** Internal seam for adding and testing database detection without publishing a dialect SPI. */
  @FunctionalInterface
  public interface Resolver {

    JdbcDatabase detect(DatabaseMetaData metadata) throws SQLException;
  }

  /** Translates a connection failure that happened before product detection. */
  public static StorageException translateBeforeDetection(String operation, SQLException failure) {
    return JdbcExceptions.translateBeforeDetection(operation, failure);
  }
}
