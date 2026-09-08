package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexCursor;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NullMarked
class PersistenceCollectionFactoryTest {

  private static final DocumentKey USER_1 = new DocumentKey("users", "user-1");

  @Test
  void initializesEachCreatedCollectionWithoutAddingFactoryIdentityCaching() {
    Fixture fixture = new Fixture();
    R2D1 client = R2D1.builder().collectionFactory(fixture.factory).build();

    R2D1Collection<User> first = client.collection(User.class);
    R2D1Collection<User> second = client.collection(User.class);

    assertThat(first).isNotSameAs(second);
    assertThat(fixture.initializedTypes).containsExactly(User.class, User.class);
    assertThat(fixture.events).containsExactly("initialize", "initialize");
  }

  @Test
  void putsTheDocumentBeforeItsDerivedIndexEntry() {
    Fixture fixture = new Fixture();
    R2D1Collection<User> collection = fixture.collection();
    User user = new User("user-1", "NZ", 7L);

    collection.put(user);

    assertThat(fixture.events).containsExactly("initialize", "document.put", "index.upsert");
    assertThat(fixture.documents.putKey).isEqualTo(USER_1);
    assertThat(fixture.codec.serialized).isSameAs(user);
    assertThat(fixture.indexes.upserted)
        .isEqualTo(
            new IndexEntry(
                USER_1,
                Map.of(
                    "country",
                    new IndexValue.StringValue("NZ"),
                    "rank",
                    new IndexValue.LongValue(7L))));
  }

  @Test
  void doesNotTouchTheIndexWhenTheAuthoritativePutFails() {
    Fixture fixture = new Fixture();
    StorageException failure = new StorageException.Unavailable("R2 unavailable");
    fixture.documents.putResult = CompletableFuture.failedFuture(failure);
    R2D1Collection<User> collection = fixture.collection();

    assertThatThrownBy(() -> collection.put(new User("user-1", "NZ", 7L))).isSameAs(failure);
    assertThat(fixture.events).containsExactly("initialize", "document.put");
    assertThat(fixture.indexes.upserted).isNull();
    assertThat(fixture.documents.deletedKeys).isEmpty();
  }

  @Test
  void reportsAnIndexPutFailureWithoutRollingBackTheDocument() {
    Fixture fixture = new Fixture();
    StorageException failure = new StorageException.Unavailable("D1 unavailable");
    fixture.indexes.upsertResult = CompletableFuture.failedFuture(failure);
    R2D1Collection<User> collection = fixture.collection();

    assertThatThrownBy(() -> collection.put(new User("user-1", "NZ", 7L)))
        .isInstanceOfSatisfying(
            PersistenceException.PartialFailure.class,
            partial -> {
              assertThat(partial.documentKey()).isEqualTo(USER_1);
              assertThat(partial.getCause()).isSameAs(failure);
              assertThat(partial)
                  .hasMessage("Document write succeeded but index upsert failed: users/user-1");
            });
    assertThat(fixture.events).containsExactly("initialize", "document.put", "index.upsert");
    assertThat(fixture.documents.deletedKeys).isEmpty();
  }

  @Test
  void getsAndDeserializesOnlyFromTheAuthoritativeStore() {
    Fixture fixture = new Fixture();
    fixture.documents.stored.put(USER_1, fixture.codec.serialize(new User("user-1", "NZ", 7L)));
    fixture.codec.serialized = null;
    R2D1Collection<User> collection = fixture.collection();

    Optional<User> result = collection.get("user-1");

    assertThat(result).contains(new User("user-1", "NZ", 7L));
    assertThat(fixture.events).containsExactly("initialize", "document.get");
    assertThat(fixture.indexes.queries).isEmpty();
  }

  @Test
  void mapsOnlyAuthoritativeNotFoundToAnEmptyOptional() {
    Fixture fixture = new Fixture();
    R2D1Collection<User> collection = fixture.collection();

    assertThat(collection.get("missing")).isEmpty();

    StorageException failure = new StorageException.Unavailable("R2 unavailable");
    fixture.documents.getFailures.put(USER_1, failure);
    assertThatThrownBy(() -> collection.get("user-1")).isSameAs(failure);
    assertThat(fixture.indexes.queries).isEmpty();
  }

  @Test
  void deletesTheAuthoritativeDocumentBeforeTheIndex() {
    Fixture fixture = new Fixture();
    R2D1Collection<User> collection = fixture.collection();

    collection.delete("user-1");

    assertThat(fixture.events).containsExactly("initialize", "document.delete", "index.delete");
    assertThat(fixture.documents.deletedKeys).containsExactly(USER_1);
    assertThat(fixture.indexes.deletedKeys).containsExactly(USER_1);
  }

  @Test
  void doesNotTouchTheIndexWhenTheAuthoritativeDeleteFails() {
    Fixture fixture = new Fixture();
    StorageException failure = new StorageException.Unavailable("R2 unavailable");
    fixture.documents.deleteResult = CompletableFuture.failedFuture(failure);
    R2D1Collection<User> collection = fixture.collection();

    assertThatThrownBy(() -> collection.delete("user-1")).isSameAs(failure);
    assertThat(fixture.events).containsExactly("initialize", "document.delete");
    assertThat(fixture.indexes.deletedKeys).isEmpty();
  }

  @Test
  void reportsAnIndexDeleteFailureWithoutRestoringTheDocument() {
    Fixture fixture = new Fixture();
    StorageException failure = new StorageException.Unavailable("D1 unavailable");
    fixture.indexes.deleteResult = CompletableFuture.failedFuture(failure);
    R2D1Collection<User> collection = fixture.collection();

    assertThatThrownBy(() -> collection.delete("user-1"))
        .isInstanceOfSatisfying(
            PersistenceException.PartialFailure.class,
            partial -> {
              assertThat(partial.documentKey()).isEqualTo(USER_1);
              assertThat(partial.getCause()).isSameAs(failure);
              assertThat(partial)
                  .hasMessage(
                      "Authoritative document delete succeeded but index cleanup failed: "
                          + "users/user-1");
            });
    assertThat(fixture.events).containsExactly("initialize", "document.delete", "index.delete");
    assertThat(fixture.documents.putCalls).isZero();
  }

  @Test
  void startsEveryQueryDocumentFetchAndPreservesIndexOrder() throws Exception {
    Fixture fixture = new Fixture();
    List<DocumentKey> keys =
        List.of(
            new DocumentKey("users", "C"),
            new DocumentKey("users", "A"),
            new DocumentKey("users", "B"));
    fixture.indexes.queryResult =
        CompletableFuture.completedFuture(new IndexPage(keys, Optional.empty()));
    fixture.documents.controlGets(keys);
    R2D1Collection<User> collection = fixture.collection();

    CompletableFuture<Page<User>> result =
        CompletableFuture.supplyAsync(() -> collection.query().limit(3).fetch());

    assertThat(fixture.documents.allControlledGetsStarted.await(2, TimeUnit.SECONDS)).isTrue();
    fixture.documents.completeGet("B", fixture.codec.serialize(new User("B", "NZ", 2L)));
    fixture.documents.completeGet("C", fixture.codec.serialize(new User("C", "NZ", 3L)));
    fixture.documents.completeGet("A", fixture.codec.serialize(new User("A", "NZ", 1L)));

    assertThat(result.get(2, TimeUnit.SECONDS).items())
        .extracting(User::id)
        .containsExactly("C", "A", "B");
    assertThat(fixture.documents.getKeys).containsExactlyElementsOf(keys);
  }

  @Test
  void translatesQueriesAndRoundTripsTheIndexCursor() {
    Fixture fixture = new Fixture();
    fixture.documents.stored.put(USER_1, fixture.codec.serialize(new User("user-1", "NZ", 7L)));
    IndexCursor cursor = new IndexCursor(USER_1, Optional.of(new IndexValue.LongValue(7L)));
    fixture.indexes.queryResult =
        CompletableFuture.completedFuture(new IndexPage(List.of(USER_1), Optional.of(cursor)));
    R2D1Collection<User> collection = fixture.collection();

    Page<User> first =
        collection
            .query()
            .where("country")
            .eq("NZ")
            .sortBy("rank", SortDirection.DESC)
            .limit(10)
            .fetch();

    assertThat(first.nextCursor()).isNotBlank();
    IndexQuery firstQuery = fixture.indexes.queries.getFirst();
    assertThat(firstQuery.filters())
        .containsExactly(
            new IndexQuery.Filter(
                "country",
                Query.Request.ComparisonOperator.EQUAL,
                new IndexValue.StringValue("NZ")));
    assertThat(firstQuery.sort()).contains(new IndexQuery.Sort("rank", SortDirection.DESC));
    assertThat(firstQuery.limit()).isEqualTo(10);

    fixture.indexes.queryResult =
        CompletableFuture.completedFuture(new IndexPage(List.of(), Optional.empty()));
    collection
        .query()
        .sortBy("rank", SortDirection.DESC)
        .limit(10)
        .after(first.nextCursor())
        .fetch();

    assertThat(fixture.indexes.queries.get(1).cursor()).contains(cursor);
  }

  @Test
  void returnsAnEmptyPageWithoutFetchingAuthoritativeDocuments() {
    Fixture fixture = new Fixture();
    R2D1Collection<User> collection = fixture.collection();

    Page<User> page = collection.query().limit(5).fetch();

    assertThat(page.items()).isEmpty();
    assertThat(page.nextCursor()).isNull();
    assertThat(fixture.documents.getKeys).isEmpty();
  }

  @Test
  void propagatesTheOriginalCollectionInitializationFailure() {
    Fixture fixture = new Fixture();
    StorageException failure = new StorageException.Unavailable("D1 unavailable");
    PersistenceCollectionFactory factory =
        new PersistenceCollectionFactory(
            fixture.documents,
            fixture.indexes,
            fixture.codec,
            type -> CompletableFuture.failedFuture(failure));

    assertThatThrownBy(() -> factory.create(User.class)).isSameAs(failure);
    assertThat(fixture.documents.putCalls).isZero();
  }

  @Test
  void rejectsMalformedOrShapeIncompatibleCursorsBeforeQueryingTheIndex() {
    Fixture fixture = new Fixture();
    R2D1Collection<User> collection = fixture.collection();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> collection.query().limit(1).after("not-a-cursor").fetch())
        .withMessage("invalid cursor");

    String sortedCursor =
        CursorCodec.encode(new IndexCursor(USER_1, Optional.of(new IndexValue.StringValue("NZ"))));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> collection.query().limit(1).after(sortedCursor).fetch())
        .withMessage("unsorted query cursor must not include a sort value");
    assertThat(fixture.indexes.queries).isEmpty();
  }

  @Test
  void failsTheWholeQueryWhenAnIndexedDocumentIsMissing() {
    Fixture fixture = new Fixture();
    fixture.indexes.queryResult =
        CompletableFuture.completedFuture(new IndexPage(List.of(USER_1), Optional.empty()));
    DocumentNotFoundException missing = new DocumentNotFoundException(USER_1);
    fixture.documents.getFailures.put(USER_1, missing);
    R2D1Collection<User> collection = fixture.collection();

    assertThatThrownBy(() -> collection.query().limit(1).fetch())
        .isInstanceOfSatisfying(
            PersistenceException.InconsistentState.class,
            inconsistent -> {
              assertThat(inconsistent.documentKey()).isEqualTo(USER_1);
              assertThat(inconsistent.getCause()).isSameAs(missing);
            });
  }

  @Test
  void failsTheWholeQueryWithTheOriginalNonMissingStorageFailure() {
    Fixture fixture = new Fixture();
    fixture.indexes.queryResult =
        CompletableFuture.completedFuture(new IndexPage(List.of(USER_1), Optional.empty()));
    StorageException failure = new StorageException.Unavailable("R2 unavailable");
    fixture.documents.getFailures.put(USER_1, failure);
    R2D1Collection<User> collection = fixture.collection();

    assertThatThrownBy(() -> collection.query().limit(1).fetch()).isSameAs(failure);
  }

  @Test
  void validatesDocumentMetadataAndValuesBeforeStorage() {
    Fixture fixture = new Fixture();

    assertThatIllegalArgumentException()
        .isThrownBy(() -> fixture.factory.create(MissingId.class))
        .withMessageContaining("exactly one @Id");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> fixture.factory.create(DuplicateId.class))
        .withMessageContaining("exactly one @Id");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> fixture.factory.create(NonStringId.class))
        .withMessage("@Id field must use java.lang.String: id");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> fixture.factory.create(BlankCollection.class))
        .withMessageContaining("document collection must not be blank");

    R2D1Collection<User> collection = fixture.collection();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> collection.put(new User("user-1", null, 1L)))
        .withMessage("indexed field value must not be null: country");
    assertThat(fixture.documents.putCalls).isZero();
  }

  @Test
  void readsInheritedAndRecordMetadata() {
    Fixture fixture = new Fixture();
    R2D1Collection<InheritedUser> collection = fixture.factory.create(InheritedUser.class);

    collection.put(new InheritedUser("user-1", "NZ"));

    assertThat(fixture.documents.putKey).isEqualTo(USER_1);
    assertThat(fixture.indexes.upserted.values())
        .containsEntry("country", new IndexValue.StringValue("NZ"));
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullFactoryDependenciesAndCodecResults() {
    Fixture fixture = new Fixture();

    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new PersistenceCollectionFactory(
                    null, fixture.indexes, fixture.codec, type -> completedVoid()))
        .withMessage("documentStore");
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new PersistenceCollectionFactory(
                    fixture.documents, null, fixture.codec, type -> completedVoid()))
        .withMessage("indexStore");
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new PersistenceCollectionFactory(
                    fixture.documents, fixture.indexes, null, type -> completedVoid()))
        .withMessage("documentCodec");
    assertThatNullPointerException()
        .isThrownBy(
            () ->
                new PersistenceCollectionFactory(
                    fixture.documents, fixture.indexes, fixture.codec, null))
        .withMessage("collectionInitializer");

    fixture.codec.returnNullFromSerialize = true;
    assertThatNullPointerException()
        .isThrownBy(() -> fixture.collection().put(new User("user-1", "NZ", 7L)))
        .withMessage("documentCodec returned null");
    assertThat(fixture.documents.putCalls).isZero();
  }

  private static CompletionStage<@Nullable Void> completedVoid() {
    return CompletableFuture.completedFuture(null);
  }

  private static final class Fixture {

    private final List<String> events = new ArrayList<>();
    private final List<Class<?>> initializedTypes = new ArrayList<>();
    private final FakeDocumentStore documents = new FakeDocumentStore(events);
    private final FakeIndexStore indexes = new FakeIndexStore(events);
    private final UserCodec codec = new UserCodec();
    private final PersistenceCollectionFactory factory =
        new PersistenceCollectionFactory(
            documents,
            indexes,
            codec,
            type -> {
              events.add("initialize");
              initializedTypes.add(type);
              return completedVoid();
            });

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
    private final List<DocumentKey> deletedKeys = new ArrayList<>();
    private CompletionStage<@Nullable Void> putResult = completedVoid();
    private CompletionStage<@Nullable Void> deleteResult = completedVoid();
    private @Nullable DocumentKey putKey;
    private int putCalls;
    private CountDownLatch allControlledGetsStarted = new CountDownLatch(0);

    private FakeDocumentStore(List<String> events) {
      this.events = events;
    }

    @Override
    public CompletionStage<@Nullable Void> put(DocumentKey key, StoredDocument document) {
      events.add("document.put");
      putCalls++;
      putKey = key;
      stored.put(key, document);
      return putResult;
    }

    @Override
    public CompletionStage<StoredDocument> get(DocumentKey key) {
      events.add("document.get");
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
      events.add("document.delete");
      deletedKeys.add(key);
      stored.remove(key);
      return deleteResult;
    }

    private void controlGets(List<DocumentKey> keys) {
      allControlledGetsStarted = new CountDownLatch(keys.size());
      keys.forEach(key -> controlledGets.put(key, new CompletableFuture<>()));
    }

    private void completeGet(String id, StoredDocument document) {
      controlledGets.get(new DocumentKey("users", id)).complete(document);
    }
  }

  private static final class FakeIndexStore implements IndexStore {

    private final List<String> events;
    private final List<IndexQuery> queries = new ArrayList<>();
    private final List<DocumentKey> deletedKeys = new ArrayList<>();
    private CompletionStage<@Nullable Void> upsertResult = completedVoid();
    private CompletionStage<IndexPage> queryResult =
        CompletableFuture.completedFuture(new IndexPage(List.of(), Optional.empty()));
    private CompletionStage<@Nullable Void> deleteResult = completedVoid();
    private @Nullable IndexEntry upserted;

    private FakeIndexStore(List<String> events) {
      this.events = events;
    }

    @Override
    public CompletionStage<@Nullable Void> upsert(IndexEntry entry) {
      events.add("index.upsert");
      upserted = entry;
      return upsertResult;
    }

    @Override
    public CompletionStage<IndexPage> query(IndexQuery query) {
      events.add("index.query");
      queries.add(query);
      return queryResult;
    }

    @Override
    public CompletionStage<@Nullable Void> delete(DocumentKey key) {
      events.add("index.delete");
      deletedKeys.add(key);
      return deleteResult;
    }
  }

  private static final class UserCodec implements DocumentCodec {

    private @Nullable Object serialized;
    private boolean returnNullFromSerialize;

    @Override
    @SuppressWarnings("DataFlowIssue")
    public StoredDocument serialize(Object document) {
      serialized = document;
      if (returnNullFromSerialize) {
        return null;
      }
      if (document instanceof InheritedUser) {
        return new StoredDocument("inherited-user".getBytes(StandardCharsets.UTF_8));
      }
      User user = (User) document;
      String value = user.id() + "\n" + user.country() + "\n" + user.rank();
      return new StoredDocument(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public <T> T deserialize(StoredDocument document, Class<T> documentType) {
      String[] values = new String(document.content(), StandardCharsets.UTF_8).split("\\n", -1);
      return documentType.cast(new User(values[0], values[1], Long.valueOf(values[2])));
    }
  }

  @Document("users")
  private record User(@Id String id, @Index @Nullable String country, @Index Long rank) {}

  private static class UserIdentity {
    @Id private final String id;

    private UserIdentity(String id) {
      this.id = id;
    }
  }

  @Document("users")
  private static final class InheritedUser extends UserIdentity {
    @Index private final String country;

    private InheritedUser(String id, String country) {
      super(id);
      this.country = country;
    }
  }

  @Document("missing_id")
  private static final class MissingId {}

  @Document("duplicate_id")
  private static final class DuplicateId {
    @Id private String first;
    @Id private String second;
  }

  @Document("non_string_id")
  private static final class NonStringId {
    @Id private long id;
  }

  @Document(" ")
  private static final class BlankCollection {
    @Id private String id;
  }
}
