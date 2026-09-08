package dev.nexcraft.r2d1.r2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.StorageException;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

class R2DocumentStoreTest {

  private static final DocumentKey KEY = new DocumentKey("users", "user/123");
  private static final String BUCKET = "documents";

  @Test
  void putsDocumentBytesUsingTheMappedBucketAndKey() {
    RecordingS3AsyncClient recordingClient = new RecordingS3AsyncClient();
    R2DocumentStore store = new R2DocumentStore(recordingClient.client(), BUCKET);

    CompletionStage<@Nullable Void> result =
        store.put(KEY, new StoredDocument(new byte[] {1, 2, 3}));

    assertThat(result.toCompletableFuture()).isCompletedWithValue(null);
    assertThat(recordingClient.putRequest().bucket()).isEqualTo(BUCKET);
    assertThat(recordingClient.putRequest().key()).isEqualTo("users/user%2F123");
    assertThat(recordingClient.putBody().contentLength()).hasValue(3L);
    assertThat(readBody(recordingClient.putBody())).containsExactly(1, 2, 3);
  }

  @Test
  void getsDocumentBytesUsingTheMappedBucketAndKey() {
    RecordingS3AsyncClient recordingClient = new RecordingS3AsyncClient();
    recordingClient.completeGetWith(new byte[] {4, 5, 6});
    R2DocumentStore store = new R2DocumentStore(recordingClient.client(), BUCKET);

    CompletionStage<StoredDocument> result = store.get(KEY);

    assertThat(result.toCompletableFuture())
        .isCompletedWithValue(new StoredDocument(new byte[] {4, 5, 6}));
    assertThat(recordingClient.getRequest().bucket()).isEqualTo(BUCKET);
    assertThat(recordingClient.getRequest().key()).isEqualTo("users/user%2F123");
    assertThat(recordingClient.getTransformer())
        .isInstanceOf(AsyncResponseTransformer.toBytes().getClass());
  }

  @Test
  void deletesUsingTheMappedBucketAndKey() {
    RecordingS3AsyncClient recordingClient = new RecordingS3AsyncClient();
    R2DocumentStore store = new R2DocumentStore(recordingClient.client(), BUCKET);

    CompletionStage<@Nullable Void> result = store.delete(KEY);

    assertThat(result.toCompletableFuture()).isCompletedWithValue(null);
    assertThat(recordingClient.deleteRequest().bucket()).isEqualTo(BUCKET);
    assertThat(recordingClient.deleteRequest().key()).isEqualTo("users/user%2F123");
  }

  @Test
  void mapsNoSuchKeyToDocumentNotFoundWithOriginalCause() {
    RecordingS3AsyncClient recordingClient = new RecordingS3AsyncClient();
    NoSuchKeyException cause =
        NoSuchKeyException.builder().message("missing object").statusCode(404).build();
    recordingClient.failGetWith(cause);
    R2DocumentStore store = new R2DocumentStore(recordingClient.client(), BUCKET);

    assertThat(completedFailure(store.get(KEY)))
        .isInstanceOfSatisfying(
            DocumentNotFoundException.class,
            failure -> {
              assertThat(failure.documentKey()).isEqualTo(KEY);
              assertThat(failure).hasMessage("Document not found: users/user/123").hasCause(cause);
            });
  }

  @Test
  void mapsHttp404ToDocumentNotFound() {
    RecordingS3AsyncClient recordingClient = new RecordingS3AsyncClient();
    S3Exception cause = serviceFailure(404, "missing object");
    recordingClient.failGetWith(cause);
    R2DocumentStore store = new R2DocumentStore(recordingClient.client(), BUCKET);

    assertThat(completedFailure(store.get(KEY)))
        .isInstanceOf(DocumentNotFoundException.class)
        .hasCause(cause);
  }

  @Test
  void treatsDeleteNotFoundFailuresAsSuccessful() {
    RecordingS3AsyncClient noSuchKeyClient = new RecordingS3AsyncClient();
    noSuchKeyClient.failDeleteWith(
        NoSuchKeyException.builder().message("missing object").statusCode(404).build());
    RecordingS3AsyncClient http404Client = new RecordingS3AsyncClient();
    http404Client.failDeleteWith(serviceFailure(404, "missing object"));

    assertThat(
            new R2DocumentStore(noSuchKeyClient.client(), BUCKET).delete(KEY).toCompletableFuture())
        .isCompletedWithValue(null);
    assertThat(
            new R2DocumentStore(http404Client.client(), BUCKET).delete(KEY).toCompletableFuture())
        .isCompletedWithValue(null);
  }

  @Test
  void unwrapsServiceFailuresAndPreservesTheOriginalCause() {
    RecordingS3AsyncClient recordingClient = new RecordingS3AsyncClient();
    S3Exception cause = serviceFailure(503, "service unavailable");
    recordingClient.failPutWith(new CompletionException(new ExecutionException(cause)));
    R2DocumentStore store = new R2DocumentStore(recordingClient.client(), BUCKET);

    assertThat(completedFailure(store.put(KEY, new StoredDocument(new byte[0]))))
        .isInstanceOf(StorageException.class)
        .hasMessage("R2 put failed for document: users/user/123")
        .hasCause(cause);
  }

  @Test
  void mapsClientFailuresWithoutLeakingConfigurationValues() {
    RecordingS3AsyncClient recordingClient = new RecordingS3AsyncClient();
    SdkClientException cause = SdkClientException.create("transport unavailable");
    recordingClient.failDeleteWith(cause);
    R2DocumentStore store = new R2DocumentStore(recordingClient.client(), BUCKET);

    assertThat(completedFailure(store.delete(KEY)))
        .isInstanceOf(StorageException.class)
        .hasMessage("R2 delete failed for document: users/user/123")
        .hasMessageNotContaining(BUCKET)
        .hasMessageNotContaining("endpoint")
        .hasMessageNotContaining("access")
        .hasMessageNotContaining("secret")
        .hasCause(cause);
  }

  @Test
  void convertsSynchronousSdkFailuresToFailedStages() {
    RecordingS3AsyncClient putClient = new RecordingS3AsyncClient();
    RecordingS3AsyncClient getClient = new RecordingS3AsyncClient();
    RecordingS3AsyncClient deleteClient = new RecordingS3AsyncClient();
    putClient.throwOnPut(SdkClientException.create("put invocation failed"));
    getClient.throwOnGet(SdkClientException.create("get invocation failed"));
    deleteClient.throwOnDelete(SdkClientException.create("delete invocation failed"));

    CompletionStage<@Nullable Void> put =
        new R2DocumentStore(putClient.client(), BUCKET).put(KEY, new StoredDocument(new byte[0]));
    CompletionStage<StoredDocument> get = new R2DocumentStore(getClient.client(), BUCKET).get(KEY);
    CompletionStage<@Nullable Void> delete =
        new R2DocumentStore(deleteClient.client(), BUCKET).delete(KEY);

    assertThat(put).isNotNull();
    assertThat(get).isNotNull();
    assertThat(delete).isNotNull();
    assertThat(completedFailure(put)).isInstanceOf(StorageException.class);
    assertThat(completedFailure(get)).isInstanceOf(StorageException.class);
    assertThat(completedFailure(delete)).isInstanceOf(StorageException.class);
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullInputsSynchronously() {
    R2DocumentStore store = new R2DocumentStore(new RecordingS3AsyncClient().client(), BUCKET);
    StoredDocument document = new StoredDocument(new byte[0]);

    assertThatNullPointerException().isThrownBy(() -> store.put(null, document)).withMessage("key");
    assertThatNullPointerException().isThrownBy(() -> store.put(KEY, null)).withMessage("document");
    assertThatNullPointerException().isThrownBy(() -> store.get(null)).withMessage("key");
    assertThatNullPointerException().isThrownBy(() -> store.delete(null)).withMessage("key");
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void validatesInjectedClientAndBucket() {
    RecordingS3AsyncClient recordingClient = new RecordingS3AsyncClient();

    assertThatNullPointerException()
        .isThrownBy(() -> new R2DocumentStore(null, BUCKET))
        .withMessage("client");
    assertThatNullPointerException()
        .isThrownBy(() -> new R2DocumentStore(recordingClient.client(), null))
        .withMessage("bucketName");
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> new R2DocumentStore(recordingClient.client(), " "))
        .withMessage("bucketName must not be blank");
  }

  @Test
  void reusesTheClientAcrossOperations() {
    RecordingS3AsyncClient recordingClient = new RecordingS3AsyncClient();
    R2DocumentStore store = new R2DocumentStore(recordingClient.client(), BUCKET);

    store.put(KEY, new StoredDocument(new byte[] {1}));
    store.put(KEY, new StoredDocument(new byte[] {2}));

    assertThat(recordingClient.putCalls()).isEqualTo(2);
  }

  @Test
  void closesAnOwnedClientOnceAndLeavesABorrowedClientOpen() {
    RecordingS3AsyncClient ownedClient = new RecordingS3AsyncClient();
    RecordingS3AsyncClient borrowedClient = new RecordingS3AsyncClient();
    R2DocumentStore ownedStore = new R2DocumentStore(ownedClient.client(), BUCKET, true);
    R2DocumentStore borrowedStore = new R2DocumentStore(borrowedClient.client(), BUCKET);

    ownedStore.close();
    ownedStore.close();
    borrowedStore.close();

    assertThat(ownedClient.closeCalls()).isOne();
    assertThat(borrowedClient.closeCalls()).isZero();
  }

  private static byte[] readBody(AsyncRequestBody body) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean completed = new AtomicBoolean();
    body.subscribe(
        new Subscriber<>() {
          @Override
          public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            output.writeBytes(bytes);
          }

          @Override
          public void onError(Throwable throwable) {
            failure.set(throwable);
          }

          @Override
          public void onComplete() {
            completed.set(true);
          }
        });

    assertThat(failure.get()).isNull();
    assertThat(completed).isTrue();
    return output.toByteArray();
  }

  private static Throwable completedFailure(CompletionStage<?> stage) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    stage.whenComplete((ignored, throwable) -> failure.set(throwable));

    assertThat(stage.toCompletableFuture()).isCompletedExceptionally();
    return Objects.requireNonNull(failure.get(), "test stage did not complete");
  }

  private static S3Exception serviceFailure(int statusCode, String message) {
    S3Exception.Builder builder = S3Exception.builder();
    builder.statusCode(statusCode);
    builder.message(message);
    return (S3Exception) builder.build();
  }
}
