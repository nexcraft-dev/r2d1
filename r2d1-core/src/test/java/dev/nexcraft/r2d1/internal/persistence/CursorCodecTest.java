package dev.nexcraft.r2d1.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexValue;
import java.time.Instant;
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
}
