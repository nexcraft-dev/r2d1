package dev.nexcraft.r2d1.integration.support;

import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexStore;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

public final class RecordingIndexStore implements IndexStore {

  private final IndexStore delegate;
  private final List<String> events;

  public RecordingIndexStore(IndexStore delegate, List<String> events) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.events = Objects.requireNonNull(events, "events");
  }

  @Override
  public CompletionStage<@Nullable Void> clear(String collection) {
    events.add("d1.clear");
    return delegate.clear(collection);
  }

  @Override
  public CompletionStage<@Nullable Void> upsert(IndexEntry entry) {
    events.add("d1.upsert:" + entry.documentKey().id());
    return delegate.upsert(entry);
  }

  @Override
  public CompletionStage<IndexPage> query(IndexQuery query) {
    events.add("d1.query");
    return delegate.query(query);
  }

  @Override
  public CompletionStage<@Nullable Void> delete(DocumentKey key) {
    events.add("d1.delete:" + key.id());
    return delegate.delete(key);
  }
}
