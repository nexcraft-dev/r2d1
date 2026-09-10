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
import java.util.ArrayList;
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
  protected final void initializesTheSameSchemaIdempotently() {
    await(adapter().initialize(ContractDocument.class));

    assertThat(await(store().query(unsortedQuery())).documentKeys()).isEmpty();
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
  protected final void supportsStringComparisonOperators() {
    await(store().upsert(entry("a", "alpha", 10L, 1.0, false)));
    await(store().upsert(entry("b", "beta", 20L, 2.0, true)));
    await(store().upsert(entry("c", "gamma", 30L, 3.0, true)));

    assertQuery("country", ComparisonOperator.EQUAL, new IndexValue.StringValue("beta"), "b");
    assertQuery(
        "country", ComparisonOperator.NOT_EQUAL, new IndexValue.StringValue("beta"), "a", "c");
    assertQuery(
        "country", ComparisonOperator.GREATER_THAN, new IndexValue.StringValue("beta"), "c");
    assertQuery(
        "country",
        ComparisonOperator.GREATER_THAN_OR_EQUAL,
        new IndexValue.StringValue("beta"),
        "b",
        "c");
    assertQuery("country", ComparisonOperator.LESS_THAN, new IndexValue.StringValue("beta"), "a");
    assertQuery(
        "country",
        ComparisonOperator.LESS_THAN_OR_EQUAL,
        new IndexValue.StringValue("beta"),
        "a",
        "b");
  }

  @Test
  protected final void supportsLongComparisonOperators() {
    insertComparisonEntries();

    assertQuery("rank", ComparisonOperator.EQUAL, new IndexValue.LongValue(20L), "b");
    assertQuery("rank", ComparisonOperator.NOT_EQUAL, new IndexValue.LongValue(20L), "a", "c");
    assertQuery("rank", ComparisonOperator.GREATER_THAN, new IndexValue.LongValue(20L), "c");
    assertQuery(
        "rank", ComparisonOperator.GREATER_THAN_OR_EQUAL, new IndexValue.LongValue(20L), "b", "c");
    assertQuery("rank", ComparisonOperator.LESS_THAN, new IndexValue.LongValue(20L), "a");
    assertQuery(
        "rank", ComparisonOperator.LESS_THAN_OR_EQUAL, new IndexValue.LongValue(20L), "a", "b");
  }

  @Test
  protected final void supportsDoubleComparisonOperators() {
    insertComparisonEntries();

    assertQuery("score", ComparisonOperator.EQUAL, new IndexValue.DoubleValue(2.0), "b");
    assertQuery("score", ComparisonOperator.NOT_EQUAL, new IndexValue.DoubleValue(2.0), "a", "c");
    assertQuery("score", ComparisonOperator.GREATER_THAN, new IndexValue.DoubleValue(2.0), "c");
    assertQuery(
        "score",
        ComparisonOperator.GREATER_THAN_OR_EQUAL,
        new IndexValue.DoubleValue(2.0),
        "b",
        "c");
    assertQuery("score", ComparisonOperator.LESS_THAN, new IndexValue.DoubleValue(2.0), "a");
    assertQuery(
        "score", ComparisonOperator.LESS_THAN_OR_EQUAL, new IndexValue.DoubleValue(2.0), "a", "b");
  }

  @Test
  protected final void supportsBooleanEqualityOperators() {
    insertComparisonEntries();

    assertQuery("active", ComparisonOperator.EQUAL, new IndexValue.BooleanValue(true), "b", "c");
    assertQuery("active", ComparisonOperator.NOT_EQUAL, new IndexValue.BooleanValue(true), "a");
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
  protected final void sortsByDocumentIdByDefault() {
    await(store().upsert(entry("c", "NZ", 30L, 3.0, true)));
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));
    await(store().upsert(entry("b", "NZ", 20L, 2.0, true)));

    assertThat(await(store().query(unsortedQuery())).documentKeys())
        .containsExactly(key("a"), key("b"), key("c"));
  }

  @Test
  protected final void continuesQueriesWithAKeysetCursor() {
    await(store().upsert(entry("c", "NZ", 20L, 3.0, true)));
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));
    await(store().upsert(entry("b", "NZ", 10L, 2.0, true)));
    await(store().upsert(entry("e", "NZ", 30L, 5.0, true)));
    await(store().upsert(entry("d", "NZ", 20L, 4.0, true)));

    IndexPage first = await(store().query(sortedQuery(SortDirection.ASC, 2)));
    assertThat(first.documentKeys()).containsExactly(key("a"), key("b"));
    IndexCursor firstCursor = first.nextCursor().orElseThrow();

    IndexPage second =
        await(
            store()
                .query(
                    new IndexQuery(
                        COLLECTION,
                        List.of(),
                        Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
                        2,
                        Optional.of(firstCursor))));
    assertThat(second.documentKeys()).containsExactly(key("c"), key("d"));
    IndexCursor secondCursor = second.nextCursor().orElseThrow();

    IndexPage third =
        await(
            store()
                .query(
                    new IndexQuery(
                        COLLECTION,
                        List.of(),
                        Optional.of(new IndexQuery.Sort("rank", SortDirection.ASC)),
                        2,
                        Optional.of(secondCursor))));
    assertThat(third.documentKeys()).containsExactly(key("e"));
    assertThat(third.nextCursor()).isEmpty();

    List<DocumentKey> allKeys = new ArrayList<>();
    allKeys.addAll(first.documentKeys());
    allKeys.addAll(second.documentKeys());
    allKeys.addAll(third.documentKeys());
    assertThat(allKeys)
        .containsExactly(key("a"), key("b"), key("c"), key("d"), key("e"))
        .doesNotHaveDuplicates();
  }

  @Test
  protected final void continuesDescendingQueriesWithAKeysetCursor() {
    await(store().upsert(entry("c", "NZ", 20L, 3.0, true)));
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));
    await(store().upsert(entry("b", "NZ", 10L, 2.0, true)));
    await(store().upsert(entry("e", "NZ", 30L, 5.0, true)));
    await(store().upsert(entry("d", "NZ", 20L, 4.0, true)));

    IndexPage first = await(store().query(sortedQuery(SortDirection.DESC, 2)));
    IndexPage second =
        await(
            store()
                .query(
                    new IndexQuery(
                        COLLECTION,
                        List.of(),
                        Optional.of(new IndexQuery.Sort("rank", SortDirection.DESC)),
                        2,
                        first.nextCursor())));
    IndexPage third =
        await(
            store()
                .query(
                    new IndexQuery(
                        COLLECTION,
                        List.of(),
                        Optional.of(new IndexQuery.Sort("rank", SortDirection.DESC)),
                        2,
                        second.nextCursor())));

    assertThat(first.documentKeys()).containsExactly(key("e"), key("d"));
    assertThat(second.documentKeys()).containsExactly(key("c"), key("b"));
    assertThat(third.documentKeys()).containsExactly(key("a"));
    assertThat(third.nextCursor()).isEmpty();
  }

  @Test
  protected final void continuesDefaultDocumentIdQueriesWithAKeysetCursor() {
    await(store().upsert(entry("c", "NZ", 30L, 3.0, true)));
    await(store().upsert(entry("a", "NZ", 10L, 1.0, true)));
    await(store().upsert(entry("b", "NZ", 20L, 2.0, true)));

    IndexQuery firstQuery =
        new IndexQuery(COLLECTION, List.of(), Optional.empty(), 2, Optional.empty());
    IndexPage first = await(store().query(firstQuery));
    IndexPage second =
        await(
            store()
                .query(
                    new IndexQuery(
                        COLLECTION, List.of(), Optional.empty(), 2, first.nextCursor())));

    assertThat(first.documentKeys()).containsExactly(key("a"), key("b"));
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

  private void insertComparisonEntries() {
    await(store().upsert(entry("a", "alpha", 10L, 1.0, false)));
    await(store().upsert(entry("b", "beta", 20L, 2.0, true)));
    await(store().upsert(entry("c", "gamma", 30L, 3.0, true)));
  }

  private void assertQuery(
      String field, ComparisonOperator operator, IndexValue value, String... expectedIds) {
    IndexQuery query =
        new IndexQuery(
            COLLECTION,
            List.of(new IndexQuery.Filter(field, operator, value)),
            Optional.empty(),
            10,
            Optional.empty());
    assertThat(await(store().query(query)).documentKeys())
        .containsExactly(
            java.util.Arrays.stream(expectedIds)
                .map(IndexStoreContractTest::key)
                .toArray(DocumentKey[]::new));
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
