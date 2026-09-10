package dev.nexcraft.r2d1.spi.testing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexStore;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reusable semantic contract for concrete {@link IndexStore} adapters.
 *
 * <p>Adapter tests extend this class and supply an initialized storage fixture. The suite keeps
 * vendor-specific setup and failure injection behind {@link Adapter} while asserting the same
 * observable indexing behavior for every implementation.
 */
public abstract class IndexStoreContractTest {

  /** Collection name used by the reusable contract document. */
  protected static final String COLLECTION = "r2d1_index_store_contract";

  private @Nullable Adapter adapter;

  /** Creates a fresh adapter fixture for one contract test. */
  protected abstract Adapter createAdapter();

  /** Initializes and clears a fresh adapter before each contract test. */
  @BeforeEach
  protected final void initializeAdapter() {
    adapter = Objects.requireNonNull(createAdapter(), "createAdapter returned null");
    await(adapter.initialize(ContractDocument.class));
    await(store().clear(COLLECTION));
  }

  /** Releases the adapter fixture after each contract test. */
  @AfterEach
  protected final void closeAdapter() throws Exception {
    if (adapter != null) {
      adapter.close();
      adapter = null;
    }
  }

  @Test
  protected final void upsertsNewEntriesAndReplacesExistingEntries() {
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));

    assertThat(await(store().query(unsortedQuery())).documentKeys()).containsExactly(key("a"));

    await(store().upsert(entry("a", "AU", 20L, 2.0, false)));

    assertThat(await(store().query(countryQuery("NZ"))).documentKeys()).isEmpty();
    assertThat(await(store().query(countryQuery("AU"))).documentKeys()).containsExactly(key("a"));
  }

  @Test
  protected final void combinesFiltersAgainstIndexedFields() {
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));
    await(store().upsert(entry("b", "NZ", 20L, 2.0, false)));
    await(store().upsert(entry("c", "AU", 30L, 3.0, true)));

    IndexQuery query =
        new IndexQuery(
            COLLECTION,
            List.of(
                new IndexQuery.Filter(
                    "country", ComparisonOperator.EQUAL, new IndexValue.StringValue("NZ")),
                new IndexQuery.Filter(
                    "active", ComparisonOperator.EQUAL, new IndexValue.BooleanValue(true))),
            Optional.empty(),
            10,
            Optional.empty());

    assertThat(await(store().query(query)).documentKeys()).containsExactly(key("a"));
  }

  @Test
  protected final void sortsWithAStableDocumentIdTieBreak() {
    await(store().upsert(entry("c", "NZ", 20L, 3.0, true)));
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));
    await(store().upsert(entry("d", "NZ", 20L, 4.0, true)));
    await(store().upsert(entry("b", "NZ", 10L, 2.0, true)));

    assertThat(await(store().query(sortedQuery(SortDirection.ASC, 10))).documentKeys())
        .containsExactly(key("a"), key("b"), key("c"), key("d"));
    assertThat(await(store().query(sortedQuery(SortDirection.DESC, 10))).documentKeys())
        .containsExactly(key("d"), key("c"), key("b"), key("a"));
  }

  @Test
  protected final void continuesQueriesWithAKeysetCursor() {
    await(store().upsert(entry("c", "NZ", 20L, 3.0, true)));
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));
    await(store().upsert(entry("b", "NZ", 10L, 2.0, true)));

    IndexPage first = await(store().query(sortedQuery(SortDirection.ASC, 2)));
    assertThat(first.documentKeys()).containsExactly(key("a"), key("b"));
    IndexCursor cursor = first.nextCursor().orElseThrow();

    IndexPage second =
        await(
            store()
                .query(
                    new IndexQuery(
                        COLLECTION,
                        List.of(),
                        Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
                        2,
                        Optional.of(cursor))));
    assertThat(second.documentKeys()).containsExactly(key("c"));
    assertThat(second.nextCursor()).isEmpty();
  }

  @Test
  protected final void deletesIdempotently() {
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));
    await(store().upsert(entry("b", "NZ", 20L, 2.0, true)));

    await(store().delete(key("a")));
    await(store().delete(key("a")));

    assertThat(await(store().query(unsortedQuery())).documentKeys()).containsExactly(key("b"));
  }

  @Test
  protected final void clearsRowsWithoutRemovingTheCollectionSchema() {
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));

    await(store().clear(COLLECTION));
    assertThat(await(store().query(unsortedQuery())).documentKeys()).isEmpty();

    await(store().upsert(entry("b", "AU", 20L, 2.0, false)));
    assertThat(await(store().query(unsortedQuery())).documentKeys()).containsExactly(key("b"));
  }

  @Test
  protected final void translatesNativeFailuresWithoutLosingTheirCause() {
    Throwable failure = completedFailure(adapter().triggerStorageFailure());

    assertThat(failure).isInstanceOf(StorageException.class).hasCauseInstanceOf(Throwable.class);
  }

  private IndexStore store() {
    return Objects.requireNonNull(adapter().store(), "adapter returned a null store");
  }

  private Adapter adapter() {
    return Objects.requireNonNull(adapter, "adapter is not initialized");
  }

  private static IndexEntry entry(
      String id, String country, long rank, double score, boolean active) {
    return new IndexEntry(
        key(id),
        Map.of(
            "country", new IndexValue.StringValue(country),
            "rank", new IndexValue.LongValue(rank),
            "score", new IndexValue.DoubleValue(score),
            "active", new IndexValue.BooleanValue(active)));
  }

  private static DocumentKey key(String id) {
    return new DocumentKey(COLLECTION, id);
  }

  private static IndexQuery unsortedQuery() {
    return new IndexQuery(COLLECTION, List.of(), Optional.empty(), 10, Optional.empty());
  }

  private static IndexQuery countryQuery(String country) {
    return new IndexQuery(
        COLLECTION,
        List.of(
            new IndexQuery.Filter(
                "country", ComparisonOperator.EQUAL, new IndexValue.StringValue(country))),
        Optional.empty(),
        10,
        Optional.empty());
  }

  private static IndexQuery sortedQuery(SortDirection direction, int limit) {
    return new IndexQuery(
        COLLECTION,
        List.of(),
        Optional.of(new IndexQuery.Sort("rank", direction)),
        limit,
        Optional.empty());
  }

  private static <T extends @Nullable Object> T await(CompletionStage<T> stage) {
    try {
      return Objects.requireNonNull(stage, "adapter returned a null stage")
          .toCompletableFuture()
          .get(10, TimeUnit.SECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("contract operation was interrupted", failure);
    } catch (ExecutionException failure) {
      throw new AssertionError("contract operation failed", failure.getCause());
    } catch (TimeoutException failure) {
      throw new AssertionError("contract operation timed out", failure);
    }
  }

  private static Throwable completedFailure(CompletionStage<?> stage) {
    try {
      Objects.requireNonNull(stage, "adapter returned a null stage")
          .toCompletableFuture()
          .get(10, TimeUnit.SECONDS);
      throw new AssertionError("stage completed successfully");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("contract operation was interrupted", failure);
    } catch (ExecutionException failure) {
      return failure.getCause();
    } catch (TimeoutException failure) {
      throw new AssertionError("contract operation timed out", failure);
    }
  }

  /** Adapter-specific lifecycle and failure-injection hooks used by the reusable contract. */
  public interface Adapter extends AutoCloseable {

    /** Returns the concrete store under test. */
    IndexStore store();

    /** Initializes the concrete adapter for one annotated document type. */
    CompletionStage<@Nullable Void> initialize(Class<?> documentType);

    /** Triggers one native storage failure that must cross the SPI as {@link StorageException}. */
    CompletionStage<?> triggerStorageFailure();

    /** Releases test resources. */
    @Override
    void close() throws Exception;
  }

  @Document(COLLECTION)
  private static final class ContractDocument {
    @Id private String id;
    @Index private String country;
    @Index private long rank;
    @Index private double score;
    @Index private boolean active;
  }
}
