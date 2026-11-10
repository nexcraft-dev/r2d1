package dev.nexcraft.r2d1.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexValue;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

  private static final DocumentKey KEY = new DocumentKey("users", "user-1");

  @Test
  void roundTripsEveryStructuredCursorValue() {
    List<IndexCursor> cursors =
        List.of(
            new IndexCursor(KEY, Optional.empty()),
            new IndexCursor(KEY, Optional.of(new IndexValue.StringValue("NZ"))),
            new IndexCursor(KEY, Optional.of(new IndexValue.LongValue(7L))),
            new IndexCursor(KEY, Optional.of(new IndexValue.DoubleValue(1.25))),
            new IndexCursor(KEY, Optional.of(new IndexValue.BooleanValue(true))),
            new IndexCursor(
                KEY,
                Optional.of(
                    new IndexValue.TimestampValue(Instant.parse("2026-09-08T12:34:56.123Z")))));

    assertThat(cursors)
        .allSatisfy(
            cursor -> assertThat(CursorCodec.decode(CursorCodec.encode(cursor))).isEqualTo(cursor));
  }

  @Test
  void rejectsTimestampOutsideSupportedInstantRange() {
    String cursor = encodeTimestamp(Long.MAX_VALUE, 0);

    assertThatThrownBy(() -> CursorCodec.decode(cursor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid cursor");
  }

  @Test
  void rejectsNonFiniteDoubleSortValue() {
    String cursor = encodeDouble(Double.NaN);

    assertThatThrownBy(() -> CursorCodec.decode(cursor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invalid cursor");
  }

  private static String encodeTimestamp(long seconds, int nanos) {
    return encode(
        output -> {
          output.writeByte(5);
          output.writeLong(seconds);
          output.writeInt(nanos);
        });
  }

  private static String encodeDouble(double value) {
    return encode(
        output -> {
          output.writeByte(3);
          output.writeDouble(value);
        });
  }

  private static String encode(SortValueWriter sortValue) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(1);
        writeString(output, KEY.collection());
        writeString(output, KEY.id());
        sortValue.write(output);
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  @FunctionalInterface
  private interface SortValueWriter {

    void write(DataOutputStream output) throws IOException;
  }
}
