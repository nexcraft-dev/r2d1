package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.jdbc.JdbcMetadata.CollectionMetadata;
import dev.nexcraft.r2d1.jdbc.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** HSQLDB implementation of the internal JDBC index dialect. */
final class HsqldbDialect extends StandardJdbcDialect {

  HsqldbDialect() {
    super(JdbcSchemaProfile.HSQLDB);
  }

  @Override
  public void upsert(Connection connection, CollectionMetadata metadata, IndexEntry entry)
      throws SQLException {
    JdbcDatabaseScope scope = requireScope(connection);
    requireCollection(metadata, entry.documentKey().collection());
    validateEntry(metadata, entry.values());

    List<String> columns = new ArrayList<>();
    List<String> casts = new ArrayList<>();
    columns.add(quoteIdentifier(JdbcMetadata.DOCUMENT_ID));
    casts.add("CAST(? AS " + profile().sqlType(JdbcValueType.STRING) + ")");
    for (IndexedField field : metadata.indexedFields()) {
      columns.add(quoteIdentifier(field.name()));
      casts.add("CAST(? AS " + profile().sqlType(profile().valueType(field)) + ")");
    }
    String incomingColumns = String.join(", ", columns);
    StringBuilder sql =
        new StringBuilder("MERGE INTO ")
            .append(scope.table(metadata.collection()))
            .append(" AS target USING (VALUES (")
            .append(String.join(", ", casts))
            .append(")) AS incoming(")
            .append(incomingColumns)
            .append(") ON target.")
            .append(quoteIdentifier(JdbcMetadata.DOCUMENT_ID))
            .append(" = incoming.")
            .append(quoteIdentifier(JdbcMetadata.DOCUMENT_ID));
    if (!metadata.indexedFields().isEmpty()) {
      sql.append(" WHEN MATCHED THEN UPDATE SET ");
      List<String> updates = new ArrayList<>();
      for (IndexedField field : metadata.indexedFields()) {
        String column = quoteIdentifier(field.name());
        updates.add("target." + column + " = incoming." + column);
      }
      sql.append(String.join(", ", updates));
    }
    sql.append(" WHEN NOT MATCHED THEN INSERT (")
        .append(incomingColumns)
        .append(") VALUES (")
        .append(
            columns.stream().map(column -> "incoming." + column).collect(Collectors.joining(", ")))
        .append(")");

    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      statement.setString(1, entry.documentKey().id());
      int parameterIndex = 2;
      for (IndexedField field : metadata.indexedFields()) {
        JdbcValueType type = profile().valueType(field);
        type.bind(statement, parameterIndex++, field.name(), entry.values().get(field.name()));
      }
      statement.executeUpdate();
    }
  }

  @Override
  public StorageException translate(String operation, SQLException failure) {
    if (failure.getErrorCode() == -451) {
      return new StorageException.Unavailable("JDBC " + operation + " failed", failure);
    }
    return super.translate(operation, failure);
  }
}
