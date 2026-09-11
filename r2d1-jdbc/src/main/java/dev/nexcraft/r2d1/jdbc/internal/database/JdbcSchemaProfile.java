package dev.nexcraft.r2d1.jdbc.internal.database;

import dev.nexcraft.r2d1.jdbc.internal.metadata.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.Types;
import java.util.Objects;

/** Vendor-specific physical metadata required by the shared JDBC schema manager. */
public record JdbcSchemaProfile(
    String databaseLabel,
    String tableType,
    PhysicalType stringType,
    PhysicalType longType,
    PhysicalType doubleType,
    PhysicalType booleanType) {

  public static final JdbcSchemaProfile H2 =
      new JdbcSchemaProfile(
          "H2",
          "BASE TABLE",
          new PhysicalType("CHARACTER VARYING(1000000000)", Types.VARCHAR, 1_000_000_000),
          new PhysicalType("BIGINT", Types.BIGINT, 0),
          new PhysicalType("DOUBLE PRECISION", Types.DOUBLE, 0),
          new PhysicalType("BOOLEAN", Types.BOOLEAN, 0));
  public static final JdbcSchemaProfile HSQLDB =
      new JdbcSchemaProfile(
          "HSQLDB",
          "TABLE",
          new PhysicalType("VARCHAR(1000000000)", Types.VARCHAR, 1_000_000_000),
          new PhysicalType("BIGINT", Types.BIGINT, 0),
          new PhysicalType("DOUBLE PRECISION", Types.DOUBLE, 0),
          new PhysicalType("BOOLEAN", Types.BOOLEAN, 0));
  public static final JdbcSchemaProfile SQLITE =
      new JdbcSchemaProfile(
          "SQLite",
          "table",
          new PhysicalType("TEXT", Types.VARCHAR, 0),
          new PhysicalType("INTEGER", Types.INTEGER, 0),
          new PhysicalType("REAL", Types.DOUBLE, 0),
          new PhysicalType("INTEGER", Types.INTEGER, 0));

  public JdbcSchemaProfile {
    Objects.requireNonNull(databaseLabel, "databaseLabel");
    Objects.requireNonNull(tableType, "tableType");
    Objects.requireNonNull(stringType, "stringType");
    Objects.requireNonNull(longType, "longType");
    Objects.requireNonNull(doubleType, "doubleType");
    Objects.requireNonNull(booleanType, "booleanType");
  }

  public JdbcValueType valueType(IndexedField field) {
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

  public String sqlType(JdbcValueType type) {
    return physicalType(type).sqlType();
  }

  int jdbcType(JdbcValueType type) {
    return physicalType(type).jdbcType();
  }

  boolean isCompatibleColumn(JdbcValueType type, int actualJdbcType, int columnSize) {
    PhysicalType expected = physicalType(type);
    return actualJdbcType == expected.jdbcType()
        && (type != JdbcValueType.STRING || columnSize >= expected.minimumSize());
  }

  private PhysicalType physicalType(JdbcValueType type) {
    Objects.requireNonNull(type, "type");
    return switch (type) {
      case STRING -> stringType;
      case LONG -> longType;
      case DOUBLE -> doubleType;
      case BOOLEAN -> booleanType;
    };
  }

  private record PhysicalType(String sqlType, int jdbcType, int minimumSize) {

    private PhysicalType {
      Objects.requireNonNull(sqlType, "sqlType");
      if (sqlType.isBlank()) {
        throw new IllegalArgumentException("sqlType must not be blank");
      }
      if (minimumSize < 0) {
        throw new IllegalArgumentException("minimumSize must not be negative");
      }
    }
  }
}
