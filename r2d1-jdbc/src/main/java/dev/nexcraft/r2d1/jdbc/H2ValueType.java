package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.jdbc.JdbcMetadata.IndexedField;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/** Logical R2D1 index values and their native H2 representations. */
enum H2ValueType {
  STRING("CHARACTER VARYING(1000000000)", Types.VARCHAR),
  LONG("BIGINT", Types.BIGINT),
  DOUBLE("DOUBLE PRECISION", Types.DOUBLE),
  BOOLEAN("BOOLEAN", Types.BOOLEAN);

  private static final int MAXIMUM_VARCHAR_LENGTH = 1_000_000_000;

  private final String sqlType;
  private final int jdbcType;

  H2ValueType(String sqlType, int jdbcType) {
    this.sqlType = sqlType;
    this.jdbcType = jdbcType;
  }

  static H2ValueType from(IndexedField field) {
    Objects.requireNonNull(field, "field");
    return switch (field.type()) {
      case STRING -> STRING;
      case LONG -> LONG;
      case DOUBLE -> DOUBLE;
      case BOOLEAN -> BOOLEAN;
      case TIMESTAMP ->
          throw new StorageException.Operation(
              "H2 does not support indexed field type TIMESTAMP: " + field.name());
    };
  }

  String sqlType() {
    return sqlType;
  }

  boolean isCompatibleColumn(int actualJdbcType, int columnSize) {
    return actualJdbcType == jdbcType && (this != STRING || columnSize >= MAXIMUM_VARCHAR_LENGTH);
  }

  void bind(PreparedStatement statement, int parameterIndex, String fieldName, IndexValue value)
      throws SQLException {
    requireCompatible(fieldName, value);
    switch (this) {
      case STRING -> statement.setString(parameterIndex, ((IndexValue.StringValue) value).value());
      case LONG -> statement.setLong(parameterIndex, ((IndexValue.LongValue) value).value());
      case DOUBLE -> statement.setDouble(parameterIndex, ((IndexValue.DoubleValue) value).value());
      case BOOLEAN ->
          statement.setBoolean(parameterIndex, ((IndexValue.BooleanValue) value).value());
    }
  }

  IndexValue read(ResultSet resultSet, int columnIndex, String fieldName) throws SQLException {
    return switch (this) {
      case STRING -> {
        String value = resultSet.getString(columnIndex);
        if (value == null) {
          throw invalidResult(fieldName);
        }
        yield new IndexValue.StringValue(value);
      }
      case LONG -> {
        long value = resultSet.getLong(columnIndex);
        if (resultSet.wasNull()) {
          throw invalidResult(fieldName);
        }
        yield new IndexValue.LongValue(value);
      }
      case DOUBLE -> {
        double value = resultSet.getDouble(columnIndex);
        if (resultSet.wasNull() || !Double.isFinite(value)) {
          throw invalidResult(fieldName);
        }
        yield new IndexValue.DoubleValue(value);
      }
      case BOOLEAN -> {
        boolean value = resultSet.getBoolean(columnIndex);
        if (resultSet.wasNull()) {
          throw invalidResult(fieldName);
        }
        yield new IndexValue.BooleanValue(value);
      }
    };
  }

  void requireCompatible(String fieldName, IndexValue value) {
    Objects.requireNonNull(value, "value");
    boolean compatible =
        switch (this) {
          case STRING -> value instanceof IndexValue.StringValue;
          case LONG -> value instanceof IndexValue.LongValue;
          case DOUBLE ->
              value instanceof IndexValue.DoubleValue doubleValue
                  && Double.isFinite(doubleValue.value());
          case BOOLEAN -> value instanceof IndexValue.BooleanValue;
        };
    if (!compatible) {
      throw new IllegalArgumentException(
          "index value type does not match field '" + fieldName + "': expected " + name());
    }
  }

  private static StorageException.Operation invalidResult(String fieldName) {
    return new StorageException.Operation(
        "H2 returned an invalid value for indexed field '" + fieldName + "'");
  }
}
