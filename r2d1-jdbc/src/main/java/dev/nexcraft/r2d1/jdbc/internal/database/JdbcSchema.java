package dev.nexcraft.r2d1.jdbc.internal.database;

import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import java.sql.Connection;
import java.sql.SQLException;

/** Internal boundary for database-specific schema inspection and additive initialization. */
public interface JdbcSchema {

  void initialize(
      Connection connection, JdbcDatabaseScope scope, CollectionMetadata collectionMetadata)
      throws SQLException;
}
