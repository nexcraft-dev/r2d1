package dev.nexcraft.r2d1.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * Value that can be represented in the technology-neutral index model.
 *
 * <p>Missing fields are omitted from an {@link IndexEntry}. Null and arbitrary object values are
 * intentionally unsupported.
 */
public sealed interface IndexValue
    permits IndexValue.BooleanValue,
        IndexValue.DoubleValue,
        IndexValue.LongValue,
        IndexValue.StringValue,
        IndexValue.TimestampValue {

  /**
   * Text index value.
   *
   * @param value non-null text
   */
  record StringValue(String value) implements IndexValue {

    /** Creates a validated text value. */
    public StringValue {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * Signed 64-bit integer index value.
   *
   * @param value integer value
   */
  record LongValue(long value) implements IndexValue {}

  /**
   * Double-precision floating-point index value.
   *
   * @param value floating-point value
   */
  record DoubleValue(double value) implements IndexValue {}

  /**
   * Boolean index value.
   *
   * @param value boolean value
   */
  record BooleanValue(boolean value) implements IndexValue {}

  /**
   * Instant index value.
   *
   * @param value non-null timestamp
   */
  record TimestampValue(Instant value) implements IndexValue {

    /** Creates a validated timestamp value. */
    public TimestampValue {
      Objects.requireNonNull(value, "value");
    }
  }
}
