package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NullMarked
class AsyncStorageContractTest {

  private static final DocumentKey KEY = new DocumentKey("users", "user-123");

  @Test
  void documentStoreExposesCompletionStageContracts() throws NoSuchMethodException {
    assertStageSignature(
        DocumentStore.class,
        "list",
        DocumentPage.class,
        String.class,
        DocumentCursor.class,
        int.class);
    Method list =
        DocumentStore.class.getMethod("list", String.class, DocumentCursor.class, int.class);
    assertThat(list.getAnnotatedParameterTypes()[1].isAnnotationPresent(Nullable.class)).isTrue();
    assertNullableVoidStageSignature(
        DocumentStore.class, "put", DocumentKey.class, StoredDocument.class);
    assertStageSignature(DocumentStore.class, "get", StoredDocument.class, DocumentKey.class);
    assertNullableVoidStageSignature(DocumentStore.class, "delete", DocumentKey.class);
  }

  @Test
  void indexStoreExposesCompletionStageContracts() throws NoSuchMethodException {
    assertNullableVoidStageSignature(IndexStore.class, "clear", String.class);
    assertNullableVoidStageSignature(IndexStore.class, "upsert", IndexEntry.class);
    assertStageSignature(IndexStore.class, "query", IndexPage.class, IndexQuery.class);
    assertNullableVoidStageSignature(IndexStore.class, "delete", DocumentKey.class);
  }

  @Test
  void documentStoreCompletesSuccessfulOperations() {
    FakeDocumentStore store = new FakeDocumentStore();
    StoredDocument document = new StoredDocument(new byte[] {1, 2, 3});

    assertThat(store.put(KEY, document).toCompletableFuture()).isCompletedWithValue(null);
    assertThat(store.get(KEY).toCompletableFuture()).isCompletedWithValue(document);
    assertThat(store.list("users", null, 20).toCompletableFuture())
        .isCompletedWithValue(new DocumentPage(List.of(KEY), Optional.empty()));
    assertThat(store.delete(KEY).toCompletableFuture()).isCompletedWithValue(null);
  }

  @Test
  void missingDocumentCompletesExceptionallyWithItsKey() {
    FakeDocumentStore store = new FakeDocumentStore();

    assertThat(completedFailure(store.get(KEY)))
        .isInstanceOfSatisfying(
            DocumentNotFoundException.class,
            failure -> {
              assertThat(failure.documentKey()).isEqualTo(KEY);
              assertThat(failure).hasMessage("Document not found: users/user-123");
            });
  }

  @Test
  void deleteFailureCompletesExceptionallyWithoutLosingItsCause() {
    RuntimeException cause = new RuntimeException("transport failure");
    StorageException failure = new StorageException("delete failed", cause);
    FakeDocumentStore store = new FakeDocumentStore();
    store.failDeletesWith(failure);

    assertThat(completedFailure(store.delete(KEY))).isSameAs(failure).hasCause(cause);
  }

  @Test
  void indexStoreCompletesSuccessfulOperations() {
    IndexPage page = new IndexPage(List.of(KEY), Optional.empty());
    FakeIndexStore store = new FakeIndexStore(page);
    IndexEntry entry = new IndexEntry(KEY, Map.of());
    IndexQuery query = new IndexQuery("users", List.of(), Optional.empty(), 20, Optional.empty());

    assertThat(store.upsert(entry).toCompletableFuture()).isCompletedWithValue(null);
    assertThat(store.query(query).toCompletableFuture()).isCompletedWithValue(page);
    assertThat(store.clear("users").toCompletableFuture()).isCompletedWithValue(null);
    assertThat(store.delete(KEY).toCompletableFuture()).isCompletedWithValue(null);
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void storesRejectNullInputsSynchronously() {
    FakeDocumentStore documentStore = new FakeDocumentStore();
    FakeIndexStore indexStore = new FakeIndexStore(new IndexPage(List.of(), Optional.empty()));
    StoredDocument document = new StoredDocument(new byte[0]);

    assertThatNullPointerException()
        .isThrownBy(() -> documentStore.put(null, document))
        .withMessage("key");
    assertThatNullPointerException()
        .isThrownBy(() -> documentStore.put(KEY, null))
        .withMessage("document");
    assertThatNullPointerException().isThrownBy(() -> documentStore.get(null)).withMessage("key");
    assertThatNullPointerException()
        .isThrownBy(() -> documentStore.list(null, null, 1))
        .withMessage("collection");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> documentStore.list(" ", null, 1))
        .withMessage("collection must not be blank");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> documentStore.list("users", null, 0))
        .withMessage("limit must be greater than zero");
    assertThatNullPointerException()
        .isThrownBy(() -> documentStore.delete(null))
        .withMessage("key");
    assertThatNullPointerException().isThrownBy(() -> indexStore.upsert(null)).withMessage("entry");
    assertThatNullPointerException()
        .isThrownBy(() -> indexStore.clear(null))
        .withMessage("collection");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> indexStore.clear(" "))
        .withMessage("collection must not be blank");
    assertThatNullPointerException().isThrownBy(() -> indexStore.query(null)).withMessage("query");
    assertThatNullPointerException().isThrownBy(() -> indexStore.delete(null)).withMessage("key");
  }

  private static void assertStageSignature(
      Class<?> owner, String methodName, Type resultType, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    Method method = owner.getMethod(methodName, parameterTypes);
    ParameterizedType returnType = (ParameterizedType) method.getGenericReturnType();

    assertThat(method.getReturnType()).isEqualTo(CompletionStage.class);
    assertThat(returnType.getActualTypeArguments()).containsExactly(resultType);
    assertThat(method.getExceptionTypes()).isEmpty();
  }

  private static void assertNullableVoidStageSignature(
      Class<?> owner, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
    assertStageSignature(owner, methodName, Void.class, parameterTypes);
    Method method = owner.getMethod(methodName, parameterTypes);
    AnnotatedParameterizedType returnType =
        (AnnotatedParameterizedType) method.getAnnotatedReturnType();

    assertThat(returnType.getAnnotatedActualTypeArguments()[0].isAnnotationPresent(Nullable.class))
        .isTrue();
  }

  private static Throwable completedFailure(CompletionStage<?> stage) {
    AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
    stage.whenComplete((ignored, throwable) -> failure.set(throwable));

    assertThat(stage.toCompletableFuture()).isCompletedExceptionally();
    return Objects.requireNonNull(failure.get(), "test stage did not complete");
  }

  private static final class FakeDocumentStore implements DocumentStore {

    private final Map<DocumentKey, StoredDocument> documents = new HashMap<>();
    private @Nullable StorageException deleteFailure;

    @Override
    public CompletionStage<DocumentPage> list(
        String collection, @Nullable DocumentCursor cursor, int limit) {
      Objects.requireNonNull(collection, "collection");
      if (collection.isBlank()) {
        throw new IllegalArgumentException("collection must not be blank");
      }
      if (limit <= 0) {
        throw new IllegalArgumentException("limit must be greater than zero");
      }
      return CompletableFuture.completedFuture(
          new DocumentPage(
              documents.keySet().stream()
                  .filter(key -> key.collection().equals(collection))
                  .limit(limit)
                  .toList(),
              Optional.empty()));
    }

    @Override
    public CompletionStage<@Nullable Void> put(DocumentKey key, StoredDocument document) {
      documents.put(
          Objects.requireNonNull(key, "key"), Objects.requireNonNull(document, "document"));
      return CompletableFuture.<@Nullable Void>completedFuture(null);
    }

    @Override
    public CompletionStage<StoredDocument> get(DocumentKey key) {
      Objects.requireNonNull(key, "key");
      StoredDocument document = documents.get(key);
      if (document == null) {
        return CompletableFuture.failedFuture(new DocumentNotFoundException(key));
      }
      return CompletableFuture.completedFuture(document);
    }

    @Override
    public CompletionStage<@Nullable Void> delete(DocumentKey key) {
      Objects.requireNonNull(key, "key");
      if (deleteFailure != null) {
        return CompletableFuture.failedFuture(deleteFailure);
      }
      documents.remove(key);
      return CompletableFuture.<@Nullable Void>completedFuture(null);
    }

    private void failDeletesWith(StorageException failure) {
      deleteFailure = failure;
    }
  }

  private static final class FakeIndexStore implements IndexStore {

    private final IndexPage queryResult;

    private FakeIndexStore(IndexPage queryResult) {
      this.queryResult = queryResult;
    }

    @Override
    public CompletionStage<@Nullable Void> clear(String collection) {
      Objects.requireNonNull(collection, "collection");
      if (collection.isBlank()) {
        throw new IllegalArgumentException("collection must not be blank");
      }
      return CompletableFuture.<@Nullable Void>completedFuture(null);
    }

    @Override
    public CompletionStage<@Nullable Void> upsert(IndexEntry entry) {
      Objects.requireNonNull(entry, "entry");
      return CompletableFuture.<@Nullable Void>completedFuture(null);
    }

    @Override
    public CompletionStage<IndexPage> query(IndexQuery query) {
      Objects.requireNonNull(query, "query");
      return CompletableFuture.completedFuture(queryResult);
    }

    @Override
    public CompletionStage<@Nullable Void> delete(DocumentKey key) {
      Objects.requireNonNull(key, "key");
      return CompletableFuture.<@Nullable Void>completedFuture(null);
    }
  }
}
