package dev.nexcraft.r2d1.jdbc.internal.database;

import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Logical R2D1 index values and their profile-specific JDBC representations. */
enum JdbcValueType {
  STRING,
  LONG,
  DOUBLE,
  BOOLEAN;

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

  IndexValue read(ResultSet resultSet, int columnIndex, String fieldName, JdbcSchemaProfile profile)
      throws SQLException {
    return switch (this) {
      case STRING -> {
        String value = resultSet.getString(columnIndex);
        if (value == null) {
          throw invalidResult(fieldName, profile);
        }
        yield new IndexValue.StringValue(value);
      }
      case LONG -> {
        long value = resultSet.getLong(columnIndex);
        if (resultSet.wasNull()) {
          throw invalidResult(fieldName, profile);
        }
        yield new IndexValue.LongValue(value);
      }
      case DOUBLE -> {
        double value = resultSet.getDouble(columnIndex);
        if (resultSet.wasNull() || !Double.isFinite(value)) {
          throw invalidResult(fieldName, profile);
        }
        yield new IndexValue.DoubleValue(value);
      }
      case BOOLEAN -> {
        boolean value = resultSet.getBoolean(columnIndex);
        if (resultSet.wasNull()) {
          throw invalidResult(fieldName, profile);
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

  private static StorageException.Operation invalidResult(
      String fieldName, JdbcSchemaProfile profile) {
    return new StorageException.Operation(
        profile.databaseLabel()
            + " returned an invalid value for indexed field '"
            + fieldName
            + "'");
  }
}
