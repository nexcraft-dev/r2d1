package dev.nexcraft.r2d1.r2;

import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.StorageException;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Asynchronous {@link DocumentStore} implementation using Cloudflare R2 as authoritative document
 * storage.
 *
 * <p>Each instance reuses a standard AWS SDK v2 {@link S3AsyncClient} with the asynchronous Netty
 * transport. This adapter owns a client created by its public constructor and closes that client in
 * {@link #close()}. It does not create or own an executor, scheduler, or virtual thread, and it
 * does not guarantee which thread invokes callbacks.
 *
 * <p>All I/O results complete through non-blocking {@link CompletionStage} instances. A strict
 * lookup for a missing document fails with {@link DocumentNotFoundException}; other AWS SDK
 * failures are translated to {@link StorageException}. The adapter defines no additional
 * cancellation or timeout guarantees.
 *
 * <p>Credentials are used only to create the client and are never included in adapter-generated
 * strings, logs, or error messages.
 */
public final class R2DocumentStore implements DocumentStore, AutoCloseable {

  private final S3AsyncClient client;
  private final String bucketName;
  private final boolean ownsClient;
  private final AtomicBoolean clientClosed = new AtomicBoolean();

  /**
   * Creates an R2 document store that creates and owns its configured asynchronous S3 client.
   *
   * @param config R2 endpoint, credentials, bucket, and region configuration
   * @throws NullPointerException if {@code config} is {@code null}
   */
  public R2DocumentStore(R2Config config) {
    this(
        R2ClientFactory.create(Objects.requireNonNull(config, "config")),
        config.bucketName(),
        true);
  }

  R2DocumentStore(S3AsyncClient client, String bucketName) {
    this(client, bucketName, false);
  }

  R2DocumentStore(S3AsyncClient client, String bucketName, boolean ownsClient) {
    this.client = Objects.requireNonNull(client, "client");
    this.bucketName = requireBucketName(bucketName);
    this.ownsClient = ownsClient;
  }

  @Override
  public CompletionStage<@Nullable Void> put(DocumentKey key, StoredDocument document) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(document, "document");
    return executeVoid(
        key,
        Operation.PUT,
        () -> {
          PutObjectRequest request =
              PutObjectRequest.builder().bucket(bucketName).key(R2ObjectKey.from(key)).build();
          return client.putObject(request, AsyncRequestBody.fromBytes(document.content()));
        });
  }

  @Override
  public CompletionStage<StoredDocument> get(DocumentKey key) {
    Objects.requireNonNull(key, "key");
    return executeValue(
        key,
        Operation.GET,
        () -> {
          GetObjectRequest request =
              GetObjectRequest.builder().bucket(bucketName).key(R2ObjectKey.from(key)).build();
          return client
              .getObject(request, AsyncResponseTransformer.toBytes())
              .thenApply(response -> new StoredDocument(response.asByteArrayUnsafe()));
        });
  }

  @Override
  public CompletionStage<@Nullable Void> delete(DocumentKey key) {
    Objects.requireNonNull(key, "key");
    return executeVoid(
        key,
        Operation.DELETE,
        () -> {
          DeleteObjectRequest request =
              DeleteObjectRequest.builder().bucket(bucketName).key(R2ObjectKey.from(key)).build();
          return client.deleteObject(request);
        });
  }

  @Override
  public void close() {
    if (ownsClient && clientClosed.compareAndSet(false, true)) {
      client.close();
    }
  }

  private <T> CompletionStage<T> executeValue(
      DocumentKey key, Operation operation, Supplier<CompletionStage<T>> invocation) {
    try {
      CompletionStage<T> stage =
          Objects.requireNonNull(invocation.get(), "AWS SDK returned a null stage");
      CompletableFuture<T> result = new CompletableFuture<>();
      stage.whenComplete(
          (value, failure) -> {
            if (failure == null) {
              result.complete(value);
            } else {
              result.completeExceptionally(storageFailure(key, operation, unwrap(failure)));
            }
          });
      return result;
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(storageFailure(key, operation, unwrap(failure)));
    }
  }

  private CompletionStage<@Nullable Void> executeVoid(
      DocumentKey key, Operation operation, Supplier<? extends CompletionStage<?>> invocation) {
    try {
      CompletionStage<?> stage =
          Objects.requireNonNull(invocation.get(), "AWS SDK returned a null stage");
      CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
      stage.whenComplete(
          (ignored, failure) -> {
            if (failure == null) {
              result.complete(null);
              return;
            }
            Throwable cause = unwrap(failure);
            if (operation == Operation.DELETE && isNotFound(cause)) {
              result.complete(null);
            } else {
              result.completeExceptionally(storageFailure(key, operation, cause));
            }
          });
      return result;
    } catch (RuntimeException failure) {
      Throwable cause = unwrap(failure);
      if (operation == Operation.DELETE && isNotFound(cause)) {
        return CompletableFuture.<@Nullable Void>completedFuture(null);
      }
      return CompletableFuture.failedFuture(storageFailure(key, operation, cause));
    }
  }

  private static StorageException storageFailure(
      DocumentKey key, Operation operation, Throwable cause) {
    if (operation == Operation.GET && isNotFound(cause)) {
      DocumentNotFoundException failure = new DocumentNotFoundException(key);
      failure.initCause(cause);
      return failure;
    }
    return new StorageException(
        "R2 " + operation.label + " failed for document: " + key.collection() + "/" + key.id(),
        cause);
  }

  private static boolean isNotFound(Throwable cause) {
    return cause instanceof NoSuchKeyException
        || (cause instanceof S3Exception serviceFailure && serviceFailure.statusCode() == 404);
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = Objects.requireNonNull(failure, "failure");
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null
        && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private static String requireBucketName(String bucketName) {
    Objects.requireNonNull(bucketName, "bucketName");
    if (bucketName.isBlank()) {
      throw new IllegalArgumentException("bucketName must not be blank");
    }
    return bucketName;
  }

  private enum Operation {
    PUT("put"),
    GET("get"),
    DELETE("delete");

    private final String label;

    Operation(String label) {
      this.label = label;
    }
  }
}
