package dev.nexcraft.r2d1.r2;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

@NullMarked
final class RecordingS3AsyncClient implements InvocationHandler {

  private final S3AsyncClient client;
  private CompletableFuture<PutObjectResponse> putResult =
      CompletableFuture.completedFuture(PutObjectResponse.builder().build());
  private CompletableFuture<ResponseBytes<GetObjectResponse>> getResult =
      CompletableFuture.completedFuture(
          ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[0]));
  private CompletableFuture<DeleteObjectResponse> deleteResult =
      CompletableFuture.completedFuture(DeleteObjectResponse.builder().build());
  private @Nullable RuntimeException synchronousPutFailure;
  private @Nullable RuntimeException synchronousGetFailure;
  private @Nullable RuntimeException synchronousDeleteFailure;
  private @Nullable PutObjectRequest putRequest;
  private @Nullable AsyncRequestBody putBody;
  private @Nullable GetObjectRequest getRequest;
  private @Nullable AsyncResponseTransformer<?, ?> getTransformer;
  private @Nullable DeleteObjectRequest deleteRequest;
  private int putCalls;
  private int closeCalls;

  RecordingS3AsyncClient() {
    client =
        (S3AsyncClient)
            Proxy.newProxyInstance(
                S3AsyncClient.class.getClassLoader(), new Class<?>[] {S3AsyncClient.class}, this);
  }

  S3AsyncClient client() {
    return client;
  }

  void completeGetWith(byte[] content) {
    getResult =
        CompletableFuture.completedFuture(
            ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content));
  }

  void failPutWith(Throwable failure) {
    putResult = CompletableFuture.failedFuture(failure);
  }

  void failGetWith(Throwable failure) {
    getResult = CompletableFuture.failedFuture(failure);
  }

  void failDeleteWith(Throwable failure) {
    deleteResult = CompletableFuture.failedFuture(failure);
  }

  void throwOnPut(RuntimeException failure) {
    synchronousPutFailure = failure;
  }

  void throwOnGet(RuntimeException failure) {
    synchronousGetFailure = failure;
  }

  void throwOnDelete(RuntimeException failure) {
    synchronousDeleteFailure = failure;
  }

  PutObjectRequest putRequest() {
    return Objects.requireNonNull(putRequest, "no put request recorded");
  }

  AsyncRequestBody putBody() {
    return Objects.requireNonNull(putBody, "no put body recorded");
  }

  GetObjectRequest getRequest() {
    return Objects.requireNonNull(getRequest, "no get request recorded");
  }

  AsyncResponseTransformer<?, ?> getTransformer() {
    return Objects.requireNonNull(getTransformer, "no get transformer recorded");
  }

  DeleteObjectRequest deleteRequest() {
    return Objects.requireNonNull(deleteRequest, "no delete request recorded");
  }

  int putCalls() {
    return putCalls;
  }

  int closeCalls() {
    return closeCalls;
  }

  @Override
  public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] arguments) {
    return switch (method.getName()) {
      case "putObject" -> invokePut(requireArguments(method, arguments));
      case "getObject" -> invokeGet(requireArguments(method, arguments));
      case "deleteObject" -> invokeDelete(requireArguments(method, arguments));
      case "close" -> {
        closeCalls++;
        yield null;
      }
      case "toString" -> "RecordingS3AsyncClient";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == requireArguments(method, arguments)[0];
      default ->
          throw new UnsupportedOperationException("Unexpected S3AsyncClient method: " + method);
    };
  }

  private static Object[] requireArguments(Method method, Object @Nullable [] arguments) {
    return Objects.requireNonNull(arguments, () -> "No arguments for " + method.getName());
  }

  private CompletableFuture<PutObjectResponse> invokePut(Object[] arguments) {
    if (synchronousPutFailure != null) {
      throw synchronousPutFailure;
    }
    putCalls++;
    putRequest = (PutObjectRequest) arguments[0];
    putBody = (AsyncRequestBody) arguments[1];
    return putResult;
  }

  private CompletableFuture<ResponseBytes<GetObjectResponse>> invokeGet(Object[] arguments) {
    if (synchronousGetFailure != null) {
      throw synchronousGetFailure;
    }
    getRequest = (GetObjectRequest) arguments[0];
    getTransformer = (AsyncResponseTransformer<?, ?>) arguments[1];
    return getResult;
  }

  private CompletableFuture<DeleteObjectResponse> invokeDelete(Object[] arguments) {
    if (synchronousDeleteFailure != null) {
      throw synchronousDeleteFailure;
    }
    deleteRequest = (DeleteObjectRequest) arguments[0];
    return deleteResult;
  }
}
