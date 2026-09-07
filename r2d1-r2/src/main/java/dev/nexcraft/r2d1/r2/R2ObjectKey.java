package dev.nexcraft.r2d1.r2;

import dev.nexcraft.r2d1.spi.DocumentKey;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Converts a {@link DocumentKey} into a collision-free R2 object key. */
final class R2ObjectKey {

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private R2ObjectKey() {}

  static String from(DocumentKey documentKey) {
    Objects.requireNonNull(documentKey, "documentKey");
    return encodeSegment(documentKey.collection()) + "/" + encodeSegment(documentKey.id());
  }

  private static String encodeSegment(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(bytes.length);
    for (byte current : bytes) {
      int unsigned = current & 0xFF;
      if (isUnreserved(unsigned)) {
        encoded.append((char) unsigned);
      } else {
        encoded.append('%').append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0F]);
      }
    }
    return encoded.toString();
  }

  private static boolean isUnreserved(int value) {
    return (value >= 'a' && value <= 'z')
        || (value >= 'A' && value <= 'Z')
        || (value >= '0' && value <= '9')
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~';
  }
}
