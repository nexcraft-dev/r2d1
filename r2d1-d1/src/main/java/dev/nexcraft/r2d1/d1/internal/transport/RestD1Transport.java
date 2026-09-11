package dev.nexcraft.r2d1.d1.internal.transport;

import dev.nexcraft.r2d1.d1.D1Config;
import dev.nexcraft.r2d1.d1.internal.sql.D1Parameter;
import dev.nexcraft.r2d1.d1.internal.sql.D1Result;
import dev.nexcraft.r2d1.d1.internal.sql.D1Statement;
import dev.nexcraft.r2d1.d1.internal.transport.D1Protocol.ApiError;
import dev.nexcraft.r2d1.d1.internal.transport.D1Protocol.ApiResponse;
import dev.nexcraft.r2d1.d1.internal.transport.D1Protocol.QueryRequest;
import dev.nexcraft.r2d1.d1.internal.transport.D1Protocol.QueryResult;
import dev.nexcraft.r2d1.spi.StorageException;
import io.avaje.jsonb.Jsonb;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/** Calls the Cloudflare D1 REST query API with Java's {@link HttpClient}. */
final class RestD1Transport implements D1Transport {

  private static final String API_ORIGIN = "https://api.cloudflare.com";

  private final D1Config config;
  private final HttpClient client;
  private final Jsonb jsonb;
  private final URI queryEndpoint;
  private final boolean ownsClient;
  private final AtomicBoolean clientClosed = new AtomicBoolean();

  RestD1Transport(D1Config config) {
    this(config, HttpClient.newHttpClient(), Jsonb.instance(), true);
  }

  RestD1Transport(D1Config config, HttpClient client) {
    this(config, client, Jsonb.instance(), false);
  }

  RestD1Transport(D1Config config, HttpClient client, Jsonb jsonb, boolean ownsClient) {
    this.config = Objects.requireNonNull(config, "config");
    this.client = Objects.requireNonNull(client, "client");
    this.jsonb = Objects.requireNonNull(jsonb, "jsonb");
    this.queryEndpoint = queryEndpoint(config);
    this.ownsClient = ownsClient;
  }

  @Override
  public CompletionStage<D1Result> execute(D1Statement statement) {
    Objects.requireNonNull(statement, "statement");
    try {
      QueryRequest payload =
          new QueryRequest(
              statement.sql(),
              statement.parameters().stream().map(D1Parameter::wireValue).toList());
      String requestBody = jsonb.toJson(payload);
      HttpRequest request =
          HttpRequest.newBuilder(queryEndpoint)
              .header("Authorization", "Bearer " + config.apiToken())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
              .build();
      CompletionStage<HttpResponse<String>> response =
          Objects.requireNonNull(
              client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
              "HttpClient returned a null stage");
      CompletableFuture<D1Result> result = new CompletableFuture<>();
      response.whenComplete(
          (value, failure) -> {
            if (failure != null) {
              result.completeExceptionally(unavailable(unwrap(failure)));
              return;
            }
            try {
              result.complete(mapResponse(value));
            } catch (RuntimeException mappingFailure) {
              result.completeExceptionally(mappingFailure);
            }
          });
      return result;
    } catch (RuntimeException ignored) {
      return CompletableFuture.failedFuture(
          new StorageException.Operation("D1 request could not be created"));
    }
  }

  @Override
  public void close() {
    if (ownsClient && clientClosed.compareAndSet(false, true)) {
      client.close();
    }
  }

  private D1Result mapResponse(HttpResponse<String> response) {
    int status = response.statusCode();
    if (status == 401 || status == 403) {
      throw new StorageException.Access("D1 access was denied with HTTP status " + status);
    }
    if (status == 429 || status >= 500) {
      throw new StorageException.Unavailable("D1 is unavailable with HTTP status " + status);
    }
    if (status < 200 || status >= 300) {
      throw new StorageException.Operation("D1 request failed with HTTP status " + status);
    }

    ApiResponse payload;
    try {
      payload =
          Objects.requireNonNull(
              jsonb.type(ApiResponse.class).fromJson(response.body()),
              "D1 response decoded to null");
    } catch (RuntimeException ignored) {
      throw new StorageException.Operation("D1 response could not be decoded");
    }
    if (!Boolean.TRUE.equals(payload.success())) {
      throw protocolFailure("D1 API reported failure", payload.errors());
    }
    List<@Nullable QueryResult> queryResults = payload.result();
    if (queryResults == null || queryResults.size() != 1) {
      throw new StorageException.Operation("D1 response did not contain exactly one query result");
    }
    QueryResult queryResult = queryResults.getFirst();
    if (queryResult == null || !Boolean.TRUE.equals(queryResult.success())) {
      throw protocolFailure("D1 query reported failure", payload.errors());
    }
    List<Map<String, @Nullable Object>> rows = normalizeRows(queryResult.results());
    long changes =
        queryResult.meta() == null || queryResult.meta().changes() == null
            ? 0L
            : queryResult.meta().changes();
    return new D1Result(rows, changes);
  }

  private static List<Map<String, @Nullable Object>> normalizeRows(
      @Nullable List<@Nullable Map<String, @Nullable Object>> responseRows) {
    if (responseRows == null) {
      return List.of();
    }
    List<Map<String, @Nullable Object>> rows = new ArrayList<>(responseRows.size());
    for (Map<String, @Nullable Object> row : responseRows) {
      if (row == null) {
        throw new StorageException.Operation("D1 response contained an invalid null result row");
      }
      rows.add(row);
    }
    return List.copyOf(rows);
  }

  private static StorageException.Operation protocolFailure(
      String message, @Nullable List<@Nullable ApiError> errors) {
    if (errors == null || errors.isEmpty()) {
      return new StorageException.Operation(message);
    }
    List<Integer> codes = new ArrayList<>();
    for (ApiError error : errors) {
      if (error != null && error.code() != null) {
        codes.add(error.code());
      }
    }
    return codes.isEmpty()
        ? new StorageException.Operation(message)
        : new StorageException.Operation(message + " (error codes: " + codes + ")");
  }

  private static StorageException.Unavailable unavailable(Throwable cause) {
    return new StorageException.Unavailable("D1 request failed before receiving a response", cause);
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

  private static URI queryEndpoint(D1Config config) {
    return URI.create(
        API_ORIGIN
            + "/client/v4/accounts/"
            + encodePathSegment(config.accountId())
            + "/d1/database/"
            + encodePathSegment(config.databaseId())
            + "/query");
  }

  private static String encodePathSegment(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(bytes.length);
    for (byte current : bytes) {
      int unsigned = current & 0xFF;
      if (isUnreserved(unsigned)) {
        encoded.append((char) unsigned);
      } else {
        encoded.append('%');
        encoded.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
        encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0x0F, 16)));
      }
    }
    return encoded.toString();
  }

  private static boolean isUnreserved(int value) {
    return (value >= 'a' && value <= 'z')
        || (value >= 'A' && value <= 'Z')
        || (value >= '0' && value <= '9')
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~';
  }
}
