package dev.nexcraft.r2d1.quarkus.it;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.spi.StoredDocument;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;

/** Application-controlled format, independent of the extension. */
@Singleton
public final class SmokeCodec implements DocumentCodec {
  @Override
  public StoredDocument serialize(final Object document) {
    final var value = (SmokeDocument) document;
    return new StoredDocument(
        (value.id() + "\n" + value.country() + "\n" + value.name())
            .getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public <T> T deserialize(final StoredDocument document, final Class<T> type) {
    final var parts = new String(document.content(), StandardCharsets.UTF_8).split("\n", -1);
    return type.cast(new SmokeDocument(parts[0], parts[1], parts[2]));
  }
}
