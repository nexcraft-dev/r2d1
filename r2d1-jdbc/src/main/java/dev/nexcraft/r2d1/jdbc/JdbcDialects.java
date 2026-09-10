package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Objects;

/** Resolves built-in JDBC dialects before any database schema mutation occurs. */
final class JdbcDialects {

  private JdbcDialects() {}

  static JdbcDialect detect(DatabaseMetaData metadata) throws SQLException {
    String productName = Objects.requireNonNull(metadata, "metadata").getDatabaseProductName();
    if ("H2".equals(productName)) {
      return new H2Dialect();
    }
    throw new StorageException.Operation("JDBC database is not supported");
  }

  /** Internal seam for adding and testing database detection without publishing a dialect SPI. */
  @FunctionalInterface
  interface Resolver {

    JdbcDialect detect(DatabaseMetaData metadata) throws SQLException;
  }
}
