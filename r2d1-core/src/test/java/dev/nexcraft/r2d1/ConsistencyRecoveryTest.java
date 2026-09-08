package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentCursor;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NullMarked
class ConsistencyRecoveryTest {

  @Test
  void replacesStaleAndMissingIndexRowsFromAuthoritativeDocuments() {
    Fixture fixture = new Fixture();
    fixture.store(new User("A", "NZ", 1L));
    fixture.store(new User("B", "AU", 2L));
    fixture.store(new User("C", "US", 3L));
    fixture.indexes.rows.put(key("A"), entry(new User("A", "old", 9L)));
    fixture.indexes.rows.put(key("old-X"), entry(new User("old-X", "old", 9L)));

    fixture.collection().rebuildIndex();

    assertThat(fixture.indexes.rows)
        .containsExactly(
            Map.entry(key("A"), entry(new User("A", "NZ", 1L))),
            Map.entry(key("B"), entry(new User("B", "AU", 2L))),
            Map.entry(key("C"), entry(new User("C", "US", 3L))));
    assertThat(fixture.events)
        .containsExactly(
            "initialize",
            "document.list",
            "document.get:A",
            "document.get:B",
            "document.get:C",
            "index.clear",
            "index.upsert:A",
            "index.upsert:B",
            "index.upsert:C");
  }

  @Test
  void clearsAStaleIndexForAnEmptyAuthoritativeCollection() {
    Fixture fixture = new Fixture();
    fixture.indexes.rows.put(key("stale"), entry(new User("stale", "NZ", 1L)));

    fixture.collection().rebuildIndex();

    assertThat(fixture.indexes.rows).isEmpty();
    assertThat(fixture.documents.getKeys).isEmpty();
    assertThat(fixture.events).containsExactly("initialize", "document.list", "index.clear");
  }

  @Test
  void repeatedRebuildsProduceTheSameIndex() {
    Fixture fixture = new Fixture();
    fixture.store(new User("A", "NZ", 1L));
    R2D1Collection<User> collection = fixture.collection();

    collection.rebuildIndex();
    Map<DocumentKey, IndexEntry> first = Map.copyOf(fixture.indexes.rows);
    collection.rebuildIndex();
    collection.rebuildIndex();

    assertThat(fixture.indexes.rows).containsExactlyInAnyOrderEntriesOf(first);
    assertThat(fixture.indexes.clearCalls).isEqualTo(3);
  }

  @Test
  void processesEveryBoundedPageWithoutTouchingAnotherCollection() {
    Fixture fixture = new Fixture();
    for (int index = 0; index < 205; index++) {
      fixture.store(new User("user-" + index, "NZ", (long) index));
    }
    DocumentKey archiveKey = new DocumentKey("users_archive", "archive-1");
    fixture.documents.stored.put(
        archiveKey, fixture.codec.serialize(new User("archive-1", "AU", 999L)));
    fixture.indexes.rows.put(archiveKey, new IndexEntry(archiveKey, Map.of()));

    fixture.collection().rebuildIndex();

    assertThat(fixture.documents.listLimits).containsExactly(100, 100, 100);
    assertThat(fixture.documents.listCursors)
        .containsExactly(Optional.empty(), cursor(100), cursor(200));
    assertThat(fixture.indexes.rows).hasSize(206).containsKey(archiveKey);
    assertThat(fixture.documents.getKeys).doesNotContain(archiveKey);
  }

  @Test
  void startsEveryGetInTheFirstPageBeforeClearingTheIndex() throws Exception {
    Fixture fixture = new Fixture();
    List<DocumentKey> keys = List.of(key("A"), key("B"), key("C"));
    fixture.documents.controlGets(keys);
    R2D1Collection<User> collection = fixture.collection();

    CompletableFuture<Void> rebuild = CompletableFuture.runAsync(collection::rebuildIndex);

    assertThat(fixture.documents.allControlledGetsStarted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(fixture.indexes.clearCalls).isZero();
    fixture.documents.completeGet(key("C"), fixture.codec.serialize(new User("C", "US", 3L)));
    fixture.documents.completeGet(key("A"), fixture.codec.serialize(new User("A", "NZ", 1L)));
    fixture.documents.completeGet(key("B"), fixture.codec.serialize(new User("B", "AU", 2L)));

    rebuild.get(2, TimeUnit.SECONDS);
    assertThat(fixture.indexes.rows.keySet()).containsExactly(key("A"), key("B"), key("C"));
  }

  @Test
  void preservesTheIndexWhenTheFirstListFails() {
    Fixture fixture = new Fixture();
    fixture.indexes.rows.put(key("existing"), entry(new User("existing", "NZ", 1L)));
    StorageException failure = new StorageException.Unavailable("R2 unavailable");
    fixture.documents.listFailure = failure;

    assertThatThrownBy(() -> fixture.collection().rebuildIndex()).isSameAs(failure);
    assertThat(fixture.indexes.rows).containsOnlyKeys(key("existing"));
    assertThat(fixture.indexes.clearCalls).isZero();
  }

  @Test
  void preservesTheIndexWhenAFirstPageGetFails() {
    Fixture fixture = new Fixture();
    fixture.store(new User("A", "NZ", 1L));
    fixture.indexes.rows.put(key("existing"), entry(new User("existing", "NZ", 1L)));
    StorageException failure = new StorageException.Unavailable("R2 unavailable");
    fixture.documents.getFailures.put(key("A"), failure);

    assertThatThrownBy(() -> fixture.collection().rebuildIndex()).isSameAs(failure);
    assertThat(fixture.indexes.rows).containsOnlyKeys(key("existing"));
    assertThat(fixture.indexes.clearCalls).isZero();
  }

  @Test
  void preservesTheIndexWhenAFirstPageDocumentCannotBeDeserialized() {
    Fixture fixture = new Fixture();
    fixture.documents.stored.put(key("A"), bytes("corrupt"));
    fixture.indexes.rows.put(key("existing"), entry(new User("existing", "NZ", 1L)));
    IllegalArgumentException failure = new IllegalArgumentException("invalid document");
    fixture.codec.deserializeFailure = failure;

    assertThatThrownBy(() -> fixture.collection().rebuildIndex()).isSameAs(failure);
    assertThat(fixture.indexes.rows).containsOnlyKeys(key("existing"));
    assertThat(fixture.indexes.clearCalls).isZero();
  }

  @Test
  void preservesTheIndexWhenFirstPageMetadataExtractionFails() {
    Fixture fixture = new Fixture();
    fixture.documents.stored.put(key("A"), bytes("A\n<null>\n1"));
    fixture.indexes.rows.put(key("existing"), entry(new User("existing", "NZ", 1L)));

    assertThatThrownBy(() -> fixture.collection().rebuildIndex())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("indexed field value must not be null: country");
    assertThat(fixture.indexes.rows).containsOnlyKeys(key("existing"));
    assertThat(fixture.indexes.clearCalls).isZero();
  }

  @Test
  void preservesTheIndexWhenClearFails() {
    Fixture fixture = new Fixture();
    fixture.store(new User("A", "NZ", 1L));
    fixture.indexes.rows.put(key("existing"), entry(new User("existing", "NZ", 1L)));
    StorageException failure = new StorageException.Unavailable("D1 unavailable");
    fixture.indexes.clearFailure = failure;

    assertThatThrownBy(() -> fixture.collection().rebuildIndex()).isSameAs(failure);
    assertThat(fixture.indexes.rows).containsOnlyKeys(key("existing"));
  }

  @Test
  void exposesAPartialIndexWhenAnUpsertFailsAfterClear() {
    Fixture fixture = new Fixture();
    fixture.store(new User("A", "NZ", 1L));
    fixture.store(new User("B", "AU", 2L));
    fixture.store(new User("C", "US", 3L));
    fixture.indexes.rows.put(key("stale"), entry(new User("stale", "NZ", 1L)));
    StorageException failure = new StorageException.Unavailable("D1 unavailable");
    fixture.indexes.upsertFailures.put(key("B"), failure);

    assertThatThrownBy(() -> fixture.collection().rebuildIndex()).isSameAs(failure);
    assertThat(fixture.indexes.rows).containsOnlyKeys(key("A"));
  }

  @Test
  void rejectsAListedKeyWhoseDocumentContentHasAnotherIdentity() {
    Fixture fixture = new Fixture();
    fixture.documents.stored.put(key("A"), fixture.codec.serialize(new User("B", "NZ", 1L)));
    fixture.indexes.rows.put(key("existing"), entry(new User("existing", "NZ", 1L)));

    assertThatThrownBy(() -> fixture.collection().rebuildIndex())
        .isInstanceOfSatisfying(
            PersistenceException.InconsistentState.class,
            failure -> {
              assertThat(failure.documentKey()).isEqualTo(key("A"));
              assertThat(failure)
                  .hasMessage("Authoritative document key does not match its content: users/A");
            });
    assertThat(fixture.indexes.rows).containsOnlyKeys(key("existing"));
    assertThat(fixture.indexes.clearCalls).isZero();
  }

  private static Optional<DocumentCursor> cursor(int offset) {
    return Optional.of(new DocumentCursor(Integer.toString(offset)));
  }

  private static DocumentKey key(String id) {
    return new DocumentKey("users", id);
  }

  private static IndexEntry entry(User user) {
    return new IndexEntry(
        key(user.id()),
        Map.of(
            "country", new IndexValue.StringValue(user.country()),
            "rank", new IndexValue.LongValue(user.rank())));
  }

  private static StoredDocument bytes(String value) {
    return new StoredDocument(value.getBytes(StandardCharsets.UTF_8));
  }

  private static CompletionStage<@Nullable Void> completedVoid() {
    return CompletableFuture.<@Nullable Void>completedFuture(null);
  }

  private static final class Fixture {

    private final List<String> events = new ArrayList<>();
    private final FakeDocumentStore documents = new FakeDocumentStore(events);
    private final FakeIndexStore indexes = new FakeIndexStore(events);
    private final UserCodec codec = new UserCodec();
    private final PersistenceCollectionFactory factory =
        new PersistenceCollectionFactory(
            documents,
            indexes,
            codec,
            ignored -> {
              events.add("initialize");
              return completedVoid();
            });

    private void store(User user) {
      documents.stored.put(key(user.id()), codec.serialize(user));
    }

    private R2D1Collection<User> collection() {
      return factory.create(User.class);
    }
  }

  private static final class FakeDocumentStore implements DocumentStore {

    private final List<String> events;
    private final Map<DocumentKey, StoredDocument> stored = new LinkedHashMap<>();
    private final Map<DocumentKey, RuntimeException> getFailures = new LinkedHashMap<>();
    private final Map<DocumentKey, CompletableFuture<StoredDocument>> controlledGets =
        new LinkedHashMap<>();
    private final List<DocumentKey> getKeys = new ArrayList<>();
    private final List<Optional<DocumentCursor>> listCursors = new ArrayList<>();
    private final List<Integer> listLimits = new ArrayList<>();
    private CountDownLatch allControlledGetsStarted = new CountDownLatch(0);
    private @Nullable RuntimeException listFailure;

    private FakeDocumentStore(List<String> events) {
      this.events = events;
    }

    @Override
    public CompletionStage<DocumentPage> list(
        String collection, @Nullable DocumentCursor cursor, int limit) {
      events.add("document.list");
      listCursors.add(Optional.ofNullable(cursor));
      listLimits.add(limit);
      if (listFailure != null) {
        return CompletableFuture.failedFuture(listFailure);
      }
      int offset = cursor == null ? 0 : Integer.parseInt(cursor.value());
      List<DocumentKey> matching =
          stored.keySet().stream().filter(key -> key.collection().equals(collection)).toList();
      int end = Math.min(offset + limit, matching.size());
      Optional<DocumentCursor> next =
          end < matching.size()
              ? Optional.of(new DocumentCursor(Integer.toString(end)))
              : Optional.empty();
      return CompletableFuture.completedFuture(
          new DocumentPage(matching.subList(offset, end), next));
    }

    @Override
    public CompletionStage<@Nullable Void> put(DocumentKey key, StoredDocument document) {
      stored.put(key, document);
      return completedVoid();
    }

    @Override
    public CompletionStage<StoredDocument> get(DocumentKey key) {
      events.add("document.get:" + key.id());
      getKeys.add(key);
      CompletableFuture<StoredDocument> controlled = controlledGets.get(key);
      if (controlled != null) {
        allControlledGetsStarted.countDown();
        return controlled;
      }
      RuntimeException failure = getFailures.get(key);
      if (failure != null) {
        return CompletableFuture.failedFuture(failure);
      }
      StoredDocument document = stored.get(key);
      if (document == null) {
        return CompletableFuture.failedFuture(new DocumentNotFoundException(key));
      }
      return CompletableFuture.completedFuture(document);
    }

    @Override
    public CompletionStage<@Nullable Void> delete(DocumentKey key) {
      stored.remove(key);
      return completedVoid();
    }

    private void controlGets(List<DocumentKey> keys) {
      allControlledGetsStarted = new CountDownLatch(keys.size());
      keys.forEach(
          key -> {
            stored.put(key, bytes("controlled"));
            controlledGets.put(key, new CompletableFuture<>());
          });
    }

    private void completeGet(DocumentKey key, StoredDocument document) {
      Objects.requireNonNull(controlledGets.get(key), "get is not controlled").complete(document);
    }
  }

  private static final class FakeIndexStore implements IndexStore {

    private final List<String> events;
    private final Map<DocumentKey, IndexEntry> rows = new LinkedHashMap<>();
    private final Map<DocumentKey, RuntimeException> upsertFailures = new LinkedHashMap<>();
    private @Nullable RuntimeException clearFailure;
    private int clearCalls;

    private FakeIndexStore(List<String> events) {
      this.events = events;
    }

    @Override
    public CompletionStage<@Nullable Void> clear(String collection) {
      events.add("index.clear");
      clearCalls++;
      if (clearFailure != null) {
        return CompletableFuture.failedFuture(clearFailure);
      }
      rows.keySet().removeIf(key -> key.collection().equals(collection));
      return completedVoid();
    }

    @Override
    public CompletionStage<@Nullable Void> upsert(IndexEntry entry) {
      events.add("index.upsert:" + entry.documentKey().id());
      RuntimeException failure = upsertFailures.get(entry.documentKey());
      if (failure != null) {
        return CompletableFuture.failedFuture(failure);
      }
      rows.put(entry.documentKey(), entry);
      return completedVoid();
    }

    @Override
    public CompletionStage<IndexPage> query(IndexQuery query) {
      return CompletableFuture.completedFuture(new IndexPage(List.of(), Optional.empty()));
    }

    @Override
    public CompletionStage<@Nullable Void> delete(DocumentKey key) {
      rows.remove(key);
      return completedVoid();
    }
  }

  private static final class UserCodec implements DocumentCodec {

    private @Nullable RuntimeException deserializeFailure;

    @Override
    public StoredDocument serialize(Object document) {
      User user = (User) document;
      return bytes(user.id() + "\n" + user.country() + "\n" + user.rank());
    }

    @Override
    public <T> T deserialize(StoredDocument document, Class<T> documentType) {
      String value = new String(document.content(), StandardCharsets.UTF_8);
      if (deserializeFailure != null && value.equals("corrupt")) {
        throw deserializeFailure;
      }
      String[] values = value.split("\\n", -1);
      String country = values[1].equals("<null>") ? null : values[1];
      return documentType.cast(new User(values[0], country, Long.parseLong(values[2])));
    }
  }

  @Document("users")
  private record User(@Id String id, @Index @Nullable String country, @Index Long rank) {}
}
