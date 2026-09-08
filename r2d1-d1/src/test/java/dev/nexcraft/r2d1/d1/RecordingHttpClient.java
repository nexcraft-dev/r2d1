package dev.nexcraft.r2d1.d1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class RecordingHttpClient extends HttpClient {

  private CompletableFuture<HttpResponse<String>> result =
      CompletableFuture.completedFuture(new StubResponse(200, "{}"));
  private @Nullable HttpRequest request;
  private int sendCalls;
  private int closeCalls;

  void respond(int status, String body) {
    result = CompletableFuture.completedFuture(new StubResponse(status, body));
  }

  void fail(Throwable failure) {
    result = CompletableFuture.failedFuture(failure);
  }

  HttpRequest request() {
    return Objects.requireNonNull(request, "no request recorded");
  }

  int sendCalls() {
    return sendCalls;
  }

  int closeCalls() {
    return closeCalls;
  }

  String requestBody() {
    HttpRequest.BodyPublisher publisher = request().bodyPublisher().orElseThrow();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
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
            throw new AssertionError(throwable);
          }

          @Override
          public void onComplete() {}
        });
    return output.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.empty();
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public SSLContext sslContext() {
    try {
      return SSLContext.getDefault();
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new AssertionError(failure);
    }
  }

  @Override
  public SSLParameters sslParameters() {
    return new SSLParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_2;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }

  @Override
  public <T> HttpResponse<T> send(
      HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
      throws IOException, InterruptedException {
    throw new AssertionError("blocking HttpClient.send must not be used");
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
    this.request = request;
    sendCalls++;
    return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) result;
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> responseBodyHandler,
      HttpResponse.@Nullable PushPromiseHandler<T> pushPromiseHandler) {
    return sendAsync(request, responseBodyHandler);
  }

  @Override
  public WebSocket.Builder newWebSocketBuilder() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() {
    closeCalls++;
  }

  private record StubResponse(int statusCode, String body) implements HttpResponse<String> {

    @Override
    public HttpRequest request() {
      return HttpRequest.newBuilder(uri()).build();
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (name, value) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return URI.create("https://api.cloudflare.com");
    }

    @Override
    public Version version() {
      return Version.HTTP_2;
    }
  }
}
