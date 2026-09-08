package dev.nexcraft.r2d1;

import dev.nexcraft.r2d1.Query.Request;
import dev.nexcraft.r2d1.spi.DocumentCursor;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Synchronous collection facade over one fully composed asynchronous persistence pipeline. */
final class PersistentR2D1Collection<T> implements R2D1Collection<T> {

  private static final int REBUILD_PAGE_SIZE = 100;

  private final DocumentMetadata<T> metadata;
  private final DocumentStore documentStore;
  private final IndexStore indexStore;
  private final DocumentCodec documentCodec;

  PersistentR2D1Collection(
      DocumentMetadata<T> metadata,
      DocumentStore documentStore,
      IndexStore indexStore,
      DocumentCodec documentCodec) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
    this.indexStore = Objects.requireNonNull(indexStore, "indexStore");
    this.documentCodec = Objects.requireNonNull(documentCodec, "documentCodec");
  }

  @Override
  public void put(T document) {
    Objects.requireNonNull(document, "document");
    IndexEntry entry = metadata.indexEntry(document);
    DocumentKey key = entry.documentKey();
    StoredDocument storedDocument =
        Objects.requireNonNull(documentCodec.serialize(document), "documentCodec returned null");

    CompletionStage<@Nullable Void> pipeline =
        StageSupport.invoke(() -> documentStore.put(key, storedDocument))
            .thenCompose(ignored -> upsertIndex(key, entry));
    StageSupport.await(pipeline);
  }

  @Override
  public Optional<T> get(String id) {
    DocumentKey key = metadata.key(id);
    CompletionStage<Optional<StoredDocument>> storedDocument = recoverNotFound(key);
    CompletionStage<Optional<T>> pipeline =
        storedDocument.thenApply(value -> value.map(this::deserialize));
    return StageSupport.await(pipeline);
  }

  @Override
  public void delete(String id) {
    DocumentKey key = metadata.key(id);
    CompletionStage<@Nullable Void> pipeline =
        StageSupport.invoke(() -> documentStore.delete(key))
            .thenCompose(ignored -> deleteIndex(key));
    StageSupport.await(pipeline);
  }

  @Override
  public void rebuildIndex() {
    CompletionStage<@Nullable Void> pipeline =
        prepareRebuildPage(null)
            .thenCompose(
                firstPage ->
                    StageSupport.invoke(() -> indexStore.clear(metadata.collection()))
                        .thenCompose(ignored -> rebuildFrom(firstPage)));
    StageSupport.await(pipeline);
  }

  @Override
  public Query<T> query() {
    return Query.create(this::fetch);
  }

  private Page<T> fetch(Request request) {
    IndexQuery query = metadata.indexQuery(request);
    CompletionStage<Page<T>> pipeline =
        StageSupport.invoke(() -> indexStore.query(query))
            .thenCompose(
                page ->
                    fetchDocuments(page.documentKeys(), this::fetchQueryDocument)
                        .thenApply(
                            documents ->
                                new Page<>(
                                    documents,
                                    page.nextCursor().map(CursorCodec::encode).orElse(null))));
    return StageSupport.await(pipeline);
  }

  private CompletionStage<@Nullable Void> upsertIndex(DocumentKey key, IndexEntry entry) {
    return StageSupport.mapFailure(
        StageSupport.invoke(() -> indexStore.upsert(entry)),
        failure ->
            new PersistenceException.PartialFailure(
                "Document write succeeded but index upsert failed: "
                    + key.collection()
                    + "/"
                    + key.id(),
                key,
                failure));
  }

  private CompletionStage<@Nullable Void> deleteIndex(DocumentKey key) {
    return StageSupport.mapFailure(
        StageSupport.invoke(() -> indexStore.delete(key)),
        failure ->
            new PersistenceException.PartialFailure(
                "Authoritative document delete succeeded but index cleanup failed: "
                    + key.collection()
                    + "/"
                    + key.id(),
                key,
                failure));
  }

  private CompletionStage<Optional<StoredDocument>> recoverNotFound(DocumentKey key) {
    CompletableFuture<Optional<StoredDocument>> result = new CompletableFuture<>();
    StageSupport.invoke(() -> documentStore.get(key))
        .whenComplete(
            (document, failure) -> {
              if (failure == null) {
                if (document == null) {
                  result.completeExceptionally(
                      new NullPointerException("documentStore returned null document"));
                } else {
                  result.complete(Optional.of(document));
                }
                return;
              }
              Throwable cause = StageSupport.unwrap(failure);
              if (cause instanceof DocumentNotFoundException) {
                result.complete(Optional.empty());
              } else {
                result.completeExceptionally(cause);
              }
            });
    return result;
  }

  private CompletionStage<RebuildPage> prepareRebuildPage(@Nullable DocumentCursor cursor) {
    return StageSupport.invoke(
            () -> documentStore.list(metadata.collection(), cursor, REBUILD_PAGE_SIZE))
        .thenCompose(
            page -> {
              DocumentPage listedPage =
                  Objects.requireNonNull(page, "documentStore returned null page");
              validateListedKeys(listedPage.documentKeys());
              return fetchDocuments(listedPage.documentKeys(), this::fetchRebuildDocument)
                  .thenApply(
                      documents ->
                          new RebuildPage(
                              rebuildEntries(listedPage.documentKeys(), documents),
                              listedPage.nextCursor()));
            });
  }

  private CompletionStage<@Nullable Void> rebuildFrom(RebuildPage page) {
    return upsertEntries(page.entries())
        .thenCompose(
            ignored -> {
              if (page.nextCursor().isEmpty()) {
                return CompletableFuture.<@Nullable Void>completedFuture(null);
              }
              return prepareRebuildPage(page.nextCursor().orElseThrow())
                  .thenCompose(this::rebuildFrom);
            });
  }

  private CompletionStage<@Nullable Void> upsertEntries(List<IndexEntry> entries) {
    CompletionStage<@Nullable Void> pipeline =
        CompletableFuture.<@Nullable Void>completedFuture(null);
    for (IndexEntry entry : entries) {
      pipeline =
          pipeline.thenCompose(ignored -> StageSupport.invoke(() -> indexStore.upsert(entry)));
    }
    return pipeline;
  }

  private List<IndexEntry> rebuildEntries(List<DocumentKey> keys, List<T> documents) {
    List<IndexEntry> entries = new ArrayList<>(documents.size());
    for (int index = 0; index < documents.size(); index++) {
      DocumentKey listedKey = keys.get(index);
      IndexEntry entry = metadata.indexEntry(documents.get(index));
      if (!entry.documentKey().equals(listedKey)) {
        IllegalStateException mismatch =
            new IllegalStateException(
                "Document content identifies "
                    + entry.documentKey().collection()
                    + "/"
                    + entry.documentKey().id());
        throw new PersistenceException.InconsistentState(
            "Authoritative document key does not match its content: "
                + listedKey.collection()
                + "/"
                + listedKey.id(),
            listedKey,
            mismatch);
      }
      entries.add(entry);
    }
    return List.copyOf(entries);
  }

  private void validateListedKeys(List<DocumentKey> keys) {
    for (DocumentKey key : keys) {
      if (!metadata.collection().equals(key.collection())) {
        throw new PersistenceException(
            "Document listing returned a key outside collection "
                + metadata.collection()
                + ": "
                + key.collection()
                + "/"
                + key.id());
      }
    }
  }

  private CompletionStage<List<T>> fetchDocuments(
      List<DocumentKey> keys, Function<DocumentKey, CompletionStage<T>> fetcher) {
    List<CompletionStage<T>> fetches = new ArrayList<>(keys.size());
    for (DocumentKey key : keys) {
      fetches.add(fetcher.apply(key));
    }

    CompletionStage<List<T>> accumulated =
        CompletableFuture.completedFuture(new ArrayList<>(fetches.size()));
    for (CompletionStage<T> fetch : fetches) {
      accumulated =
          accumulated.thenCombine(
              fetch,
              (documents, document) -> {
                documents.add(document);
                return documents;
              });
    }
    return accumulated.thenApply(List::copyOf);
  }

  private CompletionStage<T> fetchQueryDocument(DocumentKey key) {
    return fetchDocument(
        key,
        "Index references a missing authoritative document: " + key.collection() + "/" + key.id());
  }

  private CompletionStage<T> fetchRebuildDocument(DocumentKey key) {
    return fetchDocument(
        key,
        "Authoritative listing references a missing document: "
            + key.collection()
            + "/"
            + key.id());
  }

  private CompletionStage<T> fetchDocument(DocumentKey key, String missingMessage) {
    CompletionStage<StoredDocument> consistentDocument =
        StageSupport.mapFailure(
            StageSupport.invoke(() -> documentStore.get(key)),
            failure -> {
              if (failure instanceof DocumentNotFoundException) {
                return new PersistenceException.InconsistentState(missingMessage, key, failure);
              }
              return failure;
            });
    return consistentDocument.thenApply(
        document ->
            deserialize(Objects.requireNonNull(document, "documentStore returned null document")));
  }

  private T deserialize(StoredDocument document) {
    return Objects.requireNonNull(
        documentCodec.deserialize(document, metadata.documentType()),
        "documentCodec returned null");
  }

  private record RebuildPage(List<IndexEntry> entries, Optional<DocumentCursor> nextCursor) {}
}
