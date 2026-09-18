package dev.nexcraft.r2d1;

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import java.util.Objects;

/** Built-in JSON codec backed by the repository's existing Avaje generated-adapter path. */
final class JsonDocumentCodec<T> implements DocumentCodec<T> {

  private static final String FORMAT = "json";
  private static final String ID = "avaje-jsonb-3";

  private final JsonType<T> type;

  private JsonDocumentCodec(Class<T> documentType) {
    type = Jsonb.instance().type(Objects.requireNonNull(documentType, "documentType"));
  }

  static <T> JsonDocumentCodec<T> forType(Class<T> documentType) {
    return new JsonDocumentCodec<>(documentType);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String format() {
    return FORMAT;
  }

  @Override
  public byte[] encode(T document) {
    return type.toJsonBytes(Objects.requireNonNull(document, "document"));
  }

  @Override
  public T decode(byte[] data) {
    return Objects.requireNonNull(
        type.fromJson(Objects.requireNonNull(data, "data")), "decoded document");
  }
}
