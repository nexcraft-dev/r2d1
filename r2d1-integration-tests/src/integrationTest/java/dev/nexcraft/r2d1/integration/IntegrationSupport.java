package dev.nexcraft.r2d1.integration;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.d1.D1IndexStore;
import dev.nexcraft.r2d1.integration.IntegrationDocuments.Value;
import dev.nexcraft.r2d1.spi.DocumentCursor;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class IntegrationSupport {

  private static final int CLEANUP_PAGE_SIZE = 100;
  private static final long OPERATION_TIMEOUT_SECONDS = 60;

  private IntegrationSupport() {}

  static <T> T await(CompletionStage<T> stage) {
    try {
      return stage.toCompletableFuture().get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Integration operation was interrupted", failure);
    } catch (TimeoutException failure) {
      throw new AssertionError("Integration operation timed out", failure);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new AssertionError("Integration operation failed", cause);
    }
  }

  static void clearR2Collection(DocumentStore store, String collection) {
    List<DocumentKey> keys = new ArrayList<>();
    DocumentCursor cursor = null;
    do {
      DocumentPage page = await(store.list(collection, cursor, CLEANUP_PAGE_SIZE));
      keys.addAll(page.documentKeys());
      cursor = page.nextCursor().orElse(null);
    } while (cursor != null);
    for (DocumentKey key : keys) {
      await(store.delete(key));
    }
  }

  static void clearD1Collection(D1IndexStore store, Class<?> documentType, String collection) {
    await(store.initialize(documentType));
    await(store.clear(collection));
  }

  static IndexEntry entry(String collection, Value document) {
    return new IndexEntry(
        new DocumentKey(collection, document.id()),
        Map.of(
            "country",
            new IndexValue.StringValue(document.country()),
            "rank",
            new IndexValue.LongValue(document.rank())));
  }

  static IndexQuery sortedCountryQuery(String collection, String country, int limit) {
    return new IndexQuery(
        collection,
        List.of(
            new IndexQuery.Filter(
                "country", ComparisonOperator.EQUAL, new IndexValue.StringValue(country))),
        Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
        limit,
        Optional.empty());
  }

  static IndexQuery sortedCountryQuery(
      String collection, String country, int limit, IndexCursor cursor) {
    return new IndexQuery(
        collection,
        List.of(
            new IndexQuery.Filter(
                "country", ComparisonOperator.EQUAL, new IndexValue.StringValue(country))),
        Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
        limit,
        Optional.of(cursor));
  }
}
