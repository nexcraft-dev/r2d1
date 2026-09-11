package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.jdbc.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Types;
import java.util.Objects;

/** Vendor-specific physical metadata required by the shared JDBC schema manager. */
record JdbcSchemaProfile(
    String databaseLabel, String tableType, String stringSqlType, int stringMinimumSize) {

  static final JdbcSchemaProfile H2 =
      new JdbcSchemaProfile("H2", "BASE TABLE", "CHARACTER VARYING(1000000000)", 1_000_000_000);
  static final JdbcSchemaProfile HSQLDB =
      new JdbcSchemaProfile("HSQLDB", "TABLE", "VARCHAR(1000000000)", 1_000_000_000);

  JdbcSchemaProfile {
    Objects.requireNonNull(databaseLabel, "databaseLabel");
    Objects.requireNonNull(tableType, "tableType");
    Objects.requireNonNull(stringSqlType, "stringSqlType");
    if (stringMinimumSize <= 0) {
      throw new IllegalArgumentException("stringMinimumSize must be positive");
    }
  }

  JdbcValueType valueType(IndexedField field) {
    Objects.requireNonNull(field, "field");
    return switch (field.type()) {
      case STRING -> JdbcValueType.STRING;
      case LONG -> JdbcValueType.LONG;
      case DOUBLE -> JdbcValueType.DOUBLE;
      case BOOLEAN -> JdbcValueType.BOOLEAN;
      case TIMESTAMP ->
          throw new StorageException.Operation(
              databaseLabel + " does not support indexed field type TIMESTAMP: " + field.name());
    };
  }

  String sqlType(JdbcValueType type) {
    Objects.requireNonNull(type, "type");
    return switch (type) {
      case STRING -> stringSqlType;
      case LONG -> "BIGINT";
      case DOUBLE -> "DOUBLE PRECISION";
      case BOOLEAN -> "BOOLEAN";
    };
  }

  int jdbcType(JdbcValueType type) {
    Objects.requireNonNull(type, "type");
    return switch (type) {
      case STRING -> Types.VARCHAR;
      case LONG -> Types.BIGINT;
      case DOUBLE -> Types.DOUBLE;
      case BOOLEAN -> Types.BOOLEAN;
    };
  }

  boolean isCompatibleColumn(JdbcValueType type, int actualJdbcType, int columnSize) {
    return actualJdbcType == jdbcType(type)
        && (type != JdbcValueType.STRING || columnSize >= stringMinimumSize);
  }
}
