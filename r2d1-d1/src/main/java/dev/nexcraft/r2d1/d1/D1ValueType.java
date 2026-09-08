package dev.nexcraft.r2d1.d1;

import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.jspecify.annotations.Nullable;

/** Logical index types supported by D1 v1 and their SQLite representations. */
enum D1ValueType {
  STRING("TEXT", "''"),
  LONG("INTEGER", "0"),
  DOUBLE("REAL", "0.0"),
  BOOLEAN("INTEGER", "0");

  private final String sqlType;
  private final String additiveDefault;

  D1ValueType(String sqlType, String additiveDefault) {
    this.sqlType = sqlType;
    this.additiveDefault = additiveDefault;
  }

  String sqlType() {
    return sqlType;
  }

  String additiveDefault() {
    return additiveDefault;
  }

  D1Parameter parameter(String fieldName, IndexValue value) {
    requireCompatible(fieldName, value);
    return switch (this) {
      case STRING -> new D1Parameter.TextParameter(((IndexValue.StringValue) value).value());
      case LONG -> new D1Parameter.IntegerParameter(((IndexValue.LongValue) value).value());
      case DOUBLE -> new D1Parameter.RealParameter(((IndexValue.DoubleValue) value).value());
      case BOOLEAN ->
          new D1Parameter.IntegerParameter(((IndexValue.BooleanValue) value).value() ? 1L : 0L);
    };
  }

  void requireCompatible(String fieldName, IndexValue value) {
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

  IndexValue readIndexValue(String fieldName, @Nullable Object value) {
    if (value == null) {
      throw invalidResult(fieldName, "null");
    }
    return switch (this) {
      case STRING -> {
        if (value instanceof String text) {
          yield new IndexValue.StringValue(text);
        }
        throw invalidResult(fieldName, value.getClass().getSimpleName());
      }
      case LONG -> new IndexValue.LongValue(requireLong(fieldName, value));
      case DOUBLE -> {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
          yield new IndexValue.DoubleValue(number.doubleValue());
        }
        throw invalidResult(fieldName, value.getClass().getSimpleName());
      }
      case BOOLEAN -> {
        long number = requireLong(fieldName, value);
        if (number == 0L || number == 1L) {
          yield new IndexValue.BooleanValue(number == 1L);
        }
        throw invalidResult(fieldName, value.getClass().getSimpleName());
      }
    };
  }

  static D1ValueType fromJavaType(String fieldName, Class<?> javaType) {
    if (javaType == String.class) {
      return STRING;
    }
    if (javaType == long.class || javaType == Long.class) {
      return LONG;
    }
    if (javaType == double.class || javaType == Double.class) {
      return DOUBLE;
    }
    if (javaType == boolean.class || javaType == Boolean.class) {
      return BOOLEAN;
    }
    throw new IllegalArgumentException(
        "indexed field '" + fieldName + "' uses unsupported type: " + javaType.getTypeName());
  }

  private static long requireLong(String fieldName, Object value) {
    try {
      if (value instanceof Byte
          || value instanceof Short
          || value instanceof Integer
          || value instanceof Long) {
        return ((Number) value).longValue();
      }
      if (value instanceof BigInteger integer) {
        return integer.longValueExact();
      }
      if (value instanceof BigDecimal decimal) {
        return decimal.longValueExact();
      }
      if (value instanceof Float || value instanceof Double) {
        double decimal = ((Number) value).doubleValue();
        if (Double.isFinite(decimal)
            && decimal == Math.rint(decimal)
            && decimal >= Long.MIN_VALUE
            && decimal <= Long.MAX_VALUE) {
          return (long) decimal;
        }
      }
    } catch (ArithmeticException ignored) {
      // Converted to the adapter's stable protocol failure below.
    }
    throw invalidResult(fieldName, value.getClass().getSimpleName());
  }

  private static StorageException.Operation invalidResult(String fieldName, String actual) {
    return new StorageException.Operation(
        "D1 returned an invalid value for indexed field '" + fieldName + "': " + actual);
  }
}
