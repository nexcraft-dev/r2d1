package dev.nexcraft.r2d1.d1;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.spi.StorageException;
import io.avaje.jsonb.Jsonb;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class RestD1TransportTest {

  private static final String SUCCESS =
      """
      {
        "success": true,
        "result": [{
          "success": true,
          "results": [{"document_id": "user-1", "score": 4.5}],
          "meta": {"changes": 1}
        }],
        "errors": []
      }
      """;

  @Test
  void sendsAnAuthenticatedAsyncRequestAndMapsTheSuccessfulResponse() {
    RecordingHttpClient client = new RecordingHttpClient();
    client.respond(200, SUCCESS);
    RestD1Transport transport =
        new RestD1Transport(new D1Config("account/id", "database id", "token-secret"), client);
    D1Statement statement =
        new D1Statement(
            "SELECT * FROM users WHERE country = ?1 AND score > ?2",
            List.of(new D1Parameter.TextParameter("NZ"), new D1Parameter.RealParameter(4.5)));

    D1Result result = transport.execute(statement).toCompletableFuture().join();

    HttpRequest request = client.request();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.uri().toString())
        .isEqualTo(
            "https://api.cloudflare.com/client/v4/accounts/account%2Fid/d1/database/"
                + "database%20id/query");
    assertThat(request.headers().firstValue("Authorization")).contains("Bearer token-secret");
    assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(client.requestBody())
        .isEqualTo(
            "{\"sql\":\"SELECT * FROM users WHERE country = ?1 AND score > ?2\","
                + "\"params\":[\"NZ\",\"4.5\"]}");
    assertThat(result.changes()).isEqualTo(1);
    assertThat(result.rows()).hasSize(1);
    assertThat(result.rows().get(0))
        .containsEntry("document_id", "user-1")
        .containsEntry("score", 4.5);
    assertThat(client.sendCalls()).isEqualTo(1);
  }

  @Test
  void mapsHttpStatusesWithoutReadingOrExposingTheResponseBody() {
    RecordingHttpClient client = new RecordingHttpClient();
    RestD1Transport transport =
        new RestD1Transport(new D1Config("account", "database", "token-secret"), client);

    client.respond(401, "token-secret sensitive-param");
    assertSafeFailure(transport, StorageException.Access.class, "401");
    client.respond(403, "token-secret sensitive-param");
    assertSafeFailure(transport, StorageException.Access.class, "403");
    client.respond(429, "token-secret sensitive-param");
    assertSafeFailure(transport, StorageException.Unavailable.class, "429");
    client.respond(503, "token-secret sensitive-param");
    assertSafeFailure(transport, StorageException.Unavailable.class, "503");
    client.respond(400, "token-secret sensitive-param");
    assertSafeFailure(transport, StorageException.Operation.class, "400");

    assertThat(client.sendCalls()).isEqualTo(5);
  }

  @Test
  void rejectsOuterAndPerQueryProtocolFailuresUsingOnlySafeErrorCodes() {
    RecordingHttpClient client = new RecordingHttpClient();
    RestD1Transport transport =
        new RestD1Transport(new D1Config("account", "database", "token-secret"), client);
    client.respond(
        200,
        """
        {"success":false,"result":[],
         "errors":[null,{"code":7500,"message":"token-secret sensitive-param"}]}
        """);

    Throwable outerFailure = completedFailure(transport.execute(statement()));

    assertThat(outerFailure)
        .isInstanceOf(StorageException.Operation.class)
        .hasMessageContaining("7500")
        .hasMessageNotContaining("token-secret")
        .hasMessageNotContaining("sensitive-param");

    client.respond(
        200,
        """
        {"success":true,"result":[{"success":false,"results":[],"meta":{"changes":0}}],
         "errors":[{"code":7501,"message":"sensitive-param"}]}
        """);
    Throwable queryFailure = completedFailure(transport.execute(statement()));

    assertThat(queryFailure)
        .isInstanceOf(StorageException.Operation.class)
        .hasMessageContaining("7501")
        .hasMessageNotContaining("sensitive-param");
  }

  @Test
  void rejectsNullQueryResultsAndRowsAsProtocolFailures() {
    RecordingHttpClient client = new RecordingHttpClient();
    RestD1Transport transport =
        new RestD1Transport(new D1Config("account", "database", "token-secret"), client);
    client.respond(200, "{\"success\":true,\"result\":[null],\"errors\":[]}");

    assertThat(completedFailure(transport.execute(statement())))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("D1 query reported failure");

    client.respond(
        200,
        "{\"success\":true,\"result\":[{\"success\":true,\"results\":[null]}]," + "\"errors\":[]}");
    assertThat(completedFailure(transport.execute(statement())))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("D1 response contained an invalid null result row");
  }

  @Test
  void mapsNetworkTimeoutAndMalformedJsonFailuresWithoutExposingPayloads() {
    RecordingHttpClient client = new RecordingHttpClient();
    RestD1Transport transport =
        new RestD1Transport(new D1Config("account", "database", "token-secret"), client);
    IOException networkCause = new IOException("connection failed");
    client.fail(networkCause);

    assertThat(completedFailure(transport.execute(statement())))
        .isInstanceOf(StorageException.Unavailable.class)
        .hasCause(networkCause)
        .hasMessageNotContaining("token-secret")
        .hasMessageNotContaining("sensitive-param");

    HttpTimeoutException timeoutCause = new HttpTimeoutException("request timed out");
    client.fail(timeoutCause);
    assertThat(completedFailure(transport.execute(statement())))
        .isInstanceOf(StorageException.Unavailable.class)
        .hasCause(timeoutCause);

    client.respond(200, "token-secret sensitive-param is not JSON");
    assertThat(completedFailure(transport.execute(statement())))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("D1 response could not be decoded")
        .hasNoCause();

    client.respond(200, "null");
    assertThat(completedFailure(transport.execute(statement())))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("D1 response could not be decoded")
        .hasNoCause();
    assertThat(client.sendCalls()).isEqualTo(4);
  }

  @Test
  void closesOnlyAnOwnedClientAndDoesSoOnce() {
    RecordingHttpClient client = new RecordingHttpClient();
    RestD1Transport owned =
        new RestD1Transport(
            new D1Config("account", "database", "token"), client, Jsonb.instance(), true);

    owned.close();
    owned.close();

    assertThat(client.closeCalls()).isEqualTo(1);

    RecordingHttpClient externalClient = new RecordingHttpClient();
    new RestD1Transport(new D1Config("account", "database", "token"), externalClient).close();
    assertThat(externalClient.closeCalls()).isZero();
  }

  private static D1Statement statement() {
    return new D1Statement(
        "SELECT document_id FROM users WHERE country = ?1",
        List.of(new D1Parameter.TextParameter("sensitive-param")));
  }

  private static void assertSafeFailure(
      RestD1Transport transport, Class<? extends StorageException> type, String status) {
    Throwable failure = completedFailure(transport.execute(statement()));
    assertThat(failure)
        .isInstanceOf(type)
        .hasMessageContaining(status)
        .hasMessageNotContaining("token-secret")
        .hasMessageNotContaining("sensitive-param");
  }

  private static Throwable completedFailure(CompletionStage<?> stage) {
    try {
      stage.toCompletableFuture().join();
      throw new AssertionError("stage completed successfully");
    } catch (CompletionException failure) {
      return failure.getCause();
    }
  }
}
