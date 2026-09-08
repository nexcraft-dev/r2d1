package dev.nexcraft.r2d1;

import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexValue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Versioned opaque conversion between public cursors and structured index cursors. */
final class CursorCodec {

  private static final int VERSION = 1;
  private static final int NONE = 0;
  private static final int STRING = 1;
  private static final int LONG = 2;
  private static final int DOUBLE = 3;
  private static final int BOOLEAN = 4;
  private static final int TIMESTAMP = 5;

  private CursorCodec() {}

  static String encode(IndexCursor cursor) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        writeString(output, cursor.lastDocumentKey().collection());
        writeString(output, cursor.lastDocumentKey().id());
        writeSortValue(output, cursor.sortValue().orElse(null));
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    } catch (IOException failure) {
      throw new IllegalStateException("cursor encoding failed", failure);
    }
  }

  static IndexCursor decode(String cursor) {
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(cursor);
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
        int version = input.readUnsignedByte();
        if (version != VERSION) {
          throw new IOException("unsupported cursor version");
        }
        DocumentKey key = new DocumentKey(readString(input), readString(input));
        Optional<IndexValue> sortValue = readSortValue(input);
        if (input.available() != 0) {
          throw new IOException("cursor contains trailing data");
        }
        return new IndexCursor(key, sortValue);
      }
    } catch (IllegalArgumentException | IOException failure) {
      throw new IllegalArgumentException("invalid cursor", failure);
    }
  }

  private static void writeSortValue(DataOutputStream output, @Nullable IndexValue sortValue)
      throws IOException {
    if (sortValue == null) {
      output.writeByte(NONE);
      return;
    }
    switch (sortValue) {
      case IndexValue.StringValue value -> {
        output.writeByte(STRING);
        writeString(output, value.value());
      }
      case IndexValue.LongValue value -> {
        output.writeByte(LONG);
        output.writeLong(value.value());
      }
      case IndexValue.DoubleValue value -> {
        output.writeByte(DOUBLE);
        output.writeDouble(value.value());
      }
      case IndexValue.BooleanValue value -> {
        output.writeByte(BOOLEAN);
        output.writeBoolean(value.value());
      }
      case IndexValue.TimestampValue value -> {
        output.writeByte(TIMESTAMP);
        output.writeLong(value.value().getEpochSecond());
        output.writeInt(value.value().getNano());
      }
    }
  }

  private static Optional<IndexValue> readSortValue(DataInputStream input) throws IOException {
    return switch (input.readUnsignedByte()) {
      case NONE -> Optional.empty();
      case STRING -> Optional.of(new IndexValue.StringValue(readString(input)));
      case LONG -> Optional.of(new IndexValue.LongValue(input.readLong()));
      case DOUBLE -> Optional.of(new IndexValue.DoubleValue(input.readDouble()));
      case BOOLEAN -> Optional.of(new IndexValue.BooleanValue(readBoolean(input)));
      case TIMESTAMP ->
          Optional.of(
              new IndexValue.TimestampValue(
                  Instant.ofEpochSecond(input.readLong(), input.readInt())));
      default -> throw new IOException("cursor contains an unknown value type");
    };
  }

  private static boolean readBoolean(DataInputStream input) throws IOException {
    int value = input.readUnsignedByte();
    if (value == 0) {
      return false;
    }
    if (value == 1) {
      return true;
    }
    throw new IOException("cursor contains an invalid boolean");
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readString(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > input.available()) {
      throw new EOFException("cursor contains an invalid string length");
    }
    return new String(input.readNBytes(length), StandardCharsets.UTF_8);
  }
}
