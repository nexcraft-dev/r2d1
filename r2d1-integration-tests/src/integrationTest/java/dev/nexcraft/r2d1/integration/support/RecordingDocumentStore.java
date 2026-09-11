package dev.nexcraft.r2d1.integration.support;

import dev.nexcraft.r2d1.spi.DocumentCursor;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

public final class RecordingDocumentStore implements DocumentStore {

  private final DocumentStore delegate;
  private final List<String> events;

  public RecordingDocumentStore(DocumentStore delegate, List<String> events) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.events = Objects.requireNonNull(events, "events");
  }

  @Override
  public CompletionStage<DocumentPage> list(
      String collection, @Nullable DocumentCursor cursor, int limit) {
    events.add("r2.list");
    return delegate.list(collection, cursor, limit);
  }

  @Override
  public CompletionStage<@Nullable Void> put(DocumentKey key, StoredDocument document) {
    events.add("r2.put:" + key.id());
    return delegate.put(key, document);
  }

  @Override
  public CompletionStage<StoredDocument> get(DocumentKey key) {
    events.add("r2.get:" + key.id());
    return delegate.get(key);
  }

  @Override
  public CompletionStage<@Nullable Void> delete(DocumentKey key) {
    events.add("r2.delete:" + key.id());
    return delegate.delete(key);
  }
}
