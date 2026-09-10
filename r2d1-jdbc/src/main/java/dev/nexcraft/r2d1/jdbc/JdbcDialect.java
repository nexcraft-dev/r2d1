package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.jdbc.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Database-specific JDBC behavior kept internal until concrete dialects stabilize the contract.
 *
 * <p>The store owns the connection scope. Implementations must not close the supplied connection
 * and must close every statement and result set they create on both success and failure paths.
 */
interface JdbcDialect {

  void initialize(Connection connection, CollectionMetadata metadata) throws SQLException;

  void clear(Connection connection, CollectionMetadata metadata) throws SQLException;

  void upsert(Connection connection, CollectionMetadata metadata, IndexEntry entry)
      throws SQLException;

  IndexPage query(Connection connection, CollectionMetadata metadata, IndexQuery query)
      throws SQLException;

  void delete(Connection connection, CollectionMetadata metadata, DocumentKey key)
      throws SQLException;

  default StorageException translate(String operation, SQLException failure) {
    return JdbcExceptions.translate(operation, failure);
  }
}
