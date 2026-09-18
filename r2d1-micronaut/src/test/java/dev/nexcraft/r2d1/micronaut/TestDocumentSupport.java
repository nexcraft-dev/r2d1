package dev.nexcraft.r2d1.micronaut;

import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentCursor;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.StoredDocument;
import io.micronaut.core.annotation.ReflectiveAccess;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

final class TestDocumentSupport {

  private TestDocumentSupport() {}

  @Document("users")
  @ReflectiveAccess
  record User(@Id String id, @Index String country, String name) {}

  static final class UserCodec implements DocumentCodec<User> {

    @Override
    public String id() {
      return "test-codec-1";
    }

    @Override
    public String format() {
      return "test";
    }

    @Override
    public byte[] encode(User user) {
      String encoded = user.id() + "\n" + user.country() + "\n" + user.name();
      return encoded.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public User decode(byte[] data) {
      String[] values = new String(data, StandardCharsets.UTF_8).split("\\n", -1);
      return new User(values[0], values[1], values[2]);
    }
  }

  static final class MemoryDocumentStore implements DocumentStore {

    private final Map<DocumentKey, StoredDocument> documents = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<DocumentPage> list(
        String collection, @Nullable DocumentCursor cursor, int limit) {
      if (cursor != null) {
        return CompletableFuture.completedFuture(
            new DocumentPage(java.util.List.of(), Optional.empty()));
      }
      var keys =
          documents.keySet().stream()
              .filter(key -> key.collection().equals(collection))
              .sorted(Comparator.comparing(DocumentKey::id))
              .limit(limit)
              .toList();
      return CompletableFuture.completedFuture(new DocumentPage(keys, Optional.empty()));
    }

    @Override
    public CompletionStage<@Nullable Void> put(DocumentKey key, StoredDocument document) {
      documents.put(key, document);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<StoredDocument> get(DocumentKey key) {
      StoredDocument document = documents.get(key);
      return document == null
          ? CompletableFuture.failedFuture(new DocumentNotFoundException(key))
          : CompletableFuture.completedFuture(document);
    }

    @Override
    public CompletionStage<@Nullable Void> delete(DocumentKey key) {
      documents.remove(key);
      return CompletableFuture.completedFuture(null);
    }
  }
}
