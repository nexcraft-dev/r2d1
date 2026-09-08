package dev.nexcraft.r2d1.r2;

import dev.nexcraft.r2d1.spi.DocumentKey;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Converts a {@link DocumentKey} into a collision-free R2 object key. */
final class R2ObjectKey {

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private R2ObjectKey() {}

  static String from(DocumentKey documentKey) {
    Objects.requireNonNull(documentKey, "documentKey");
    return collectionPrefix(documentKey.collection()) + encodeSegment(documentKey.id());
  }

  static String collectionPrefix(String collection) {
    return encodeSegment(requireText(collection, "collection")) + "/";
  }

  static DocumentKey toDocumentKey(String collection, String objectKey) {
    String validatedCollection = requireText(collection, "collection");
    Objects.requireNonNull(objectKey, "objectKey");
    String prefix = collectionPrefix(validatedCollection);
    if (!objectKey.startsWith(prefix)) {
      throw new IllegalArgumentException("object key is outside the requested collection");
    }
    String encodedId = objectKey.substring(prefix.length());
    if (encodedId.isEmpty() || encodedId.indexOf('/') >= 0) {
      throw new IllegalArgumentException("object key does not contain one encoded document id");
    }
    String id = decodeSegment(encodedId);
    if (!encodeSegment(id).equals(encodedId)) {
      throw new IllegalArgumentException("object key does not use canonical segment encoding");
    }
    return new DocumentKey(validatedCollection, id);
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

  private static String decodeSegment(String value) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '%') {
        if (index + 2 >= value.length()) {
          throw new IllegalArgumentException("object key contains incomplete percent encoding");
        }
        int high = hexValue(value.charAt(index + 1));
        int low = hexValue(value.charAt(index + 2));
        bytes.write((high << 4) | low);
        index += 2;
      } else {
        if (current > 0x7F) {
          throw new IllegalArgumentException("object key contains unencoded non-ASCII text");
        }
        bytes.write(current);
      }
    }

    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString();
    } catch (CharacterCodingException failure) {
      throw new IllegalArgumentException("object key contains invalid UTF-8 encoding", failure);
    }
  }

  private static int hexValue(char value) {
    if (value >= '0' && value <= '9') {
      return value - '0';
    }
    if (value >= 'A' && value <= 'F') {
      return value - 'A' + 10;
    }
    if (value >= 'a' && value <= 'f') {
      return value - 'a' + 10;
    }
    throw new IllegalArgumentException("object key contains invalid percent encoding");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
