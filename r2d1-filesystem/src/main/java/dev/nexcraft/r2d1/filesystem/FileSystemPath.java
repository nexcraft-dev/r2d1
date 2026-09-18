package dev.nexcraft.r2d1.filesystem;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Internal collision-free mapping between R2D1 identities and filesystem names. */
final class FileSystemPath {

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();
  private static final String DOCUMENT_SUFFIX = ".r2d1";

  private FileSystemPath() {}

  static Path collectionPath(Path root, String collection) {
    return root.resolve(encodeSegment(requireText(collection, "collection")));
  }

  static Path documentPath(Path collectionDirectory, String id) {
    return collectionDirectory.resolve(documentFileName(id));
  }

  static String documentFileName(String id) {
    return encodeSegment(requireText(id, "id")) + DOCUMENT_SUFFIX;
  }

  static String temporaryPrefix(String id) {
    return "." + encodeSegment(requireText(id, "id")) + ".";
  }

  static boolean isCanonicalDocumentName(String fileName) {
    if (fileName == null || !fileName.endsWith(DOCUMENT_SUFFIX)) {
      return false;
    }
    String encodedId = fileName.substring(0, fileName.length() - DOCUMENT_SUFFIX.length());
    if (encodedId.isEmpty()) {
      return false;
    }
    try {
      String id = decodeSegment(encodedId);
      return documentFileName(id).equals(fileName);
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }

  static String requireCanonicalDocumentName(String fileName) {
    Objects.requireNonNull(fileName, "cursor value");
    if (!isCanonicalDocumentName(fileName)) {
      throw new IllegalArgumentException("invalid filesystem document cursor");
    }
    return fileName;
  }

  static String documentIdFromCanonicalName(String fileName) {
    requireCanonicalDocumentName(fileName);
    return decodeSegment(fileName.substring(0, fileName.length() - DOCUMENT_SUFFIX.length()));
  }

  private static String encodeSegment(String value) {
    String encoded = percentEncode(value);
    return requiresReservedNameMarker(value) ? "!" + encoded : encoded;
  }

  private static String decodeSegment(String encoded) {
    boolean marked = encoded.startsWith("!");
    String payload = marked ? encoded.substring(1) : encoded;
    if (payload.isEmpty()) {
      throw new IllegalArgumentException("empty encoded filesystem segment");
    }
    String decoded = percentDecode(payload);
    if (requiresReservedNameMarker(decoded) != marked || !encodeSegment(decoded).equals(encoded)) {
      throw new IllegalArgumentException("non-canonical filesystem segment");
    }
    return decoded;
  }

  private static String percentEncode(String value) {
    ByteBuffer bytes;
    try {
      bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
    } catch (CharacterCodingException failure) {
      throw new IllegalArgumentException("invalid UTF-16 filesystem segment", failure);
    }
    StringBuilder encoded = new StringBuilder(bytes.remaining());
    while (bytes.hasRemaining()) {
      int unsigned = bytes.get() & 0xFF;
      if (isUnreserved(unsigned)) {
        encoded.append((char) unsigned);
      } else {
        encoded.append('%').append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0F]);
      }
    }
    return encoded.toString();
  }

  private static String percentDecode(String value) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '%') {
        if (index + 2 >= value.length()) {
          throw new IllegalArgumentException("incomplete filesystem percent encoding");
        }
        int high = hexValue(value.charAt(index + 1));
        int low = hexValue(value.charAt(index + 2));
        bytes.write((high << 4) | low);
        index += 2;
      } else {
        if (current > 0x7F || current == '!') {
          throw new IllegalArgumentException("invalid filesystem segment encoding");
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
      throw new IllegalArgumentException("invalid UTF-8 filesystem segment", failure);
    }
  }

  private static boolean isUnreserved(int value) {
    return (value >= 'a' && value <= 'z')
        || (value >= '0' && value <= '9')
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~';
  }

  private static boolean requiresReservedNameMarker(String value) {
    return value.equals(".")
        || value.equals("..")
        || value.endsWith(".")
        || isWindowsReservedName(value);
  }

  private static boolean isWindowsReservedName(String value) {
    String trimmed = value;
    while (!trimmed.isEmpty()
        && (trimmed.charAt(trimmed.length() - 1) == '.'
            || trimmed.charAt(trimmed.length() - 1) == ' ')) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    int dot = trimmed.indexOf('.');
    String base = (dot < 0 ? trimmed : trimmed.substring(0, dot)).toUpperCase(Locale.ROOT);
    return switch (base) {
      case "CON", "PRN", "AUX", "NUL" -> true;
      default ->
          base.length() == 4
              && (base.startsWith("COM") || base.startsWith("LPT"))
              && base.charAt(3) >= '1'
              && base.charAt(3) <= '9';
    };
  }

  private static int hexValue(char value) {
    if (value >= '0' && value <= '9') {
      return value - '0';
    }
    if (value >= 'A' && value <= 'F') {
      return value - 'A' + 10;
    }
    throw new IllegalArgumentException("invalid filesystem percent encoding");
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
