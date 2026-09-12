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
    await(store().upsert(entry("a", "alpha", 10L, -2.5, false)));
    await(store().upsert(entry("b", "beta", 20L, 0.0, true)));
    await(store().upsert(entry("c", "gamma", 30L, 1.25, true)));
    await(store().upsert(entry("d", "delta", 40L, 1.25, false)));
    await(store().upsert(entry("e", "epsilon", 50L, 3.5, true)));

    assertQuery("score", ComparisonOperator.EQUAL, new IndexValue.DoubleValue(1.25), "c", "d");
    assertQuery(
        "score", ComparisonOperator.NOT_EQUAL, new IndexValue.DoubleValue(1.25), "a", "b", "e");
    assertQuery("score", ComparisonOperator.GREATER_THAN, new IndexValue.DoubleValue(1.25), "e");
    assertQuery(
        "score",
        ComparisonOperator.GREATER_THAN_OR_EQUAL,
        new IndexValue.DoubleValue(1.25),
        "c",
        "d",
        "e");
    assertQuery("score", ComparisonOperator.LESS_THAN, new IndexValue.DoubleValue(1.25), "a", "b");
    assertQuery(
        "score",
        ComparisonOperator.LESS_THAN_OR_EQUAL,
        new IndexValue.DoubleValue(1.25),
        "a",
        "b",
        "c",
        "d");
  }

  @Test
  protected final void supportsBooleanEqualityOperators() {
    insertComparisonEntries();

    assertQuery("active", ComparisonOperator.EQUAL, new IndexValue.BooleanValue(true), "b", "c");
    assertQuery("active", ComparisonOperator.NOT_EQUAL, new IndexValue.BooleanValue(true), "a");
    assertQuery("active", ComparisonOperator.EQUAL, new IndexValue.BooleanValue(false), "a");
    assertQuery(
        "active", ComparisonOperator.NOT_EQUAL, new IndexValue.BooleanValue(false), "b", "c");
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
  protected final void paginatesEverySortableTypeWithoutDuplicatesOmissionsOrOrderChanges() {
    insertCursorEntries();

    assertCursorMatrix("country", List.of("a", "k", "c", "m", "b", "z"));
    assertCursorMatrix("rank", List.of("a", "b", "c", "k", "m", "z"));
    assertCursorMatrix("score", List.of("a", "c", "m", "b", "k", "z"));
    assertCursorMatrix("active", List.of("a", "b", "c", "k", "m", "z"));
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
  protected final void handlesEmptySingleAndExactSizePages() {
    IndexPage empty = await(store().query(unsortedQuery()));
    assertThat(empty.documentKeys()).isEmpty();
    assertThat(empty.nextCursor()).isEmpty();

    await(store().upsert(entry("only", "alpha", 0L, 0.0, false)));
    IndexPage single =
        await(
            store()
                .query(
                    new IndexQuery(COLLECTION, List.of(), Optional.empty(), 1, Optional.empty())));
    assertThat(single.documentKeys()).containsExactly(key("only"));
    assertThat(single.nextCursor()).isEmpty();
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

  private void insertCursorEntries() {
    await(store().upsert(entry("m", "beta", 20L, 0.5, true)));
    await(store().upsert(entry("a", "alpha", -10L, -2.5, false)));
    await(store().upsert(entry("z", "gamma", 20L, 3.75, true)));
    await(store().upsert(entry("c", "beta", 0L, 0.0, false)));
    await(store().upsert(entry("k", "alpha", 20L, 3.75, true)));
    await(store().upsert(entry("b", "gamma", -10L, 1.25, false)));
  }

  private void assertCursorMatrix(String field, List<String> ascendingIds) {
    List<String> descendingIds = new ArrayList<>(ascendingIds);
    java.util.Collections.reverse(descendingIds);
    for (SortDirection direction : SortDirection.values()) {
      List<String> expected = direction == SortDirection.ASC ? ascendingIds : descendingIds;
      for (int pageSize : List.of(1, 2, expected.size())) {
        assertCursorTraversal(field, direction, pageSize, expected);
      }
    }
  }

  private void assertCursorTraversal(
      String field, SortDirection direction, int pageSize, List<String> expectedIds) {
    List<DocumentKey> actual = new ArrayList<>();
    Optional<IndexCursor> cursor = Optional.empty();
    int pageCount = 0;
    do {
      IndexPage page =
          await(
              store()
                  .query(
                      new IndexQuery(
                          COLLECTION,
                          List.of(),
                          Optional.of(new IndexQuery.Sort(field, direction)),
                          pageSize,
                          cursor)));
      actual.addAll(page.documentKeys());
      cursor = page.nextCursor();
      pageCount++;
      assertThat(pageCount)
          .as("cursor termination for %s %s at page size %s", field, direction, pageSize)
          .isLessThanOrEqualTo(expectedIds.size());
    } while (cursor.isPresent());

    DocumentKey[] expected =
        expectedIds.stream().map(IndexStoreContractTest::key).toArray(DocumentKey[]::new);
    assertThat(actual)
        .as("full cursor traversal for %s %s at page size %s", field, direction, pageSize)
        .containsExactly(expected)
        .doesNotHaveDuplicates();
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
