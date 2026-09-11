package dev.nexcraft.r2d1.jdbc.internal.database.h2;

import dev.nexcraft.r2d1.jdbc.internal.database.JdbcDatabaseScope;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcSchemaProfile;
import dev.nexcraft.r2d1.jdbc.internal.database.JdbcValueType;
import dev.nexcraft.r2d1.jdbc.internal.database.StandardJdbcDialect;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.util.ArrayList;
import java.util.List;

/** H2 implementation of the internal JDBC index dialect. */
public final class H2Dialect extends StandardJdbcDialect {

  public H2Dialect() {
    super(JdbcSchemaProfile.H2);
  }

  @Override
  public void upsert(Connection connection, CollectionMetadata metadata, IndexEntry entry)
      throws SQLException {
    JdbcDatabaseScope scope = requireScope(connection);
    requireCollection(metadata, entry.documentKey().collection());
    validateEntry(metadata, entry.values());

    List<String> columns = new ArrayList<>();
    List<String> placeholders = new ArrayList<>();
    columns.add(quoteIdentifier(JdbcMetadata.DOCUMENT_ID));
    placeholders.add("?");
    for (IndexedField field : metadata.indexedFields()) {
      columns.add(quoteIdentifier(field.name()));
      placeholders.add("?");
    }
    String sql =
        "MERGE INTO "
            + scope.table(metadata.collection())
            + " ("
            + String.join(", ", columns)
            + ") KEY ("
            + quoteIdentifier(JdbcMetadata.DOCUMENT_ID)
            + ") VALUES ("
            + String.join(", ", placeholders)
            + ")";
    executeWrite(
        () -> {
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.documentKey().id());
            int parameterIndex = 2;
            for (IndexedField field : metadata.indexedFields()) {
              JdbcValueType type = profile().valueType(field);
              bindValue(
                  statement,
                  parameterIndex++,
                  type,
                  field.name(),
                  entry.values().get(field.name()));
            }
            statement.executeUpdate();
          }
        });
  }

  @Override
  public StorageException translate(String operation, SQLException failure) {
    if (failure instanceof SQLTimeoutException
        || failure instanceof SQLTransactionRollbackException) {
      return new StorageException.Unavailable("JDBC " + operation + " failed", failure);
    }
    return super.translate(operation, failure);
  }
}
