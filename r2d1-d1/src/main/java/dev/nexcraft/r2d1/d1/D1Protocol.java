package dev.nexcraft.r2d1.d1;

import io.avaje.jsonb.Json;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Minimal JSON protocol DTOs for the Cloudflare D1 REST API. */
final class D1Protocol {

  private D1Protocol() {}

  @Json
  record QueryRequest(String sql, List<String> params) {}

  @Json
  record ApiResponse(
      @Nullable Boolean success,
      @Nullable List<@Nullable QueryResult> result,
      @Nullable List<@Nullable ApiError> errors) {}

  @Json
  record QueryResult(
      @Nullable Boolean success,
      @Nullable List<@Nullable Map<String, @Nullable Object>> results,
      @Nullable QueryMeta meta) {}

  @Json
  record QueryMeta(@Nullable Long changes) {}

  @Json
  record ApiError(@Nullable Integer code, @Nullable String message) {}
}
