package dev.nexcraft.r2d1.jdbc.internal.database;

import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.SQLException;

/** Internal database-operation bridge used by {@code JdbcIndexStore}; not a supported SPI. */
public interface JdbcDatabase {

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
