package dev.nexcraft.r2d1;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable builder for a restricted indexed query.
 *
 * <p>Every filter added through {@link #where(String)} is combined with logical AND. The API
 * deliberately has no SQL, OR, arbitrary predicate, full-text, regular-expression, or client-side
 * filtering entry points. Adapters are responsible for verifying that named fields are explicitly
 * indexed and compatible with the supplied values before executing the request.
 *
 * @param <T> result document type
 */
public final class Query<T> {

  private final Executor<T> executor;
  private final List<Request.Filter> filters;
  private final Request.@Nullable Sort sort;
  private final @Nullable Integer limit;
  private final @Nullable String cursor;

  private Query(
      Executor<T> executor,
      List<Request.Filter> filters,
      Request.@Nullable Sort sort,
      @Nullable Integer limit,
      @Nullable String cursor) {
    this.executor = executor;
    this.filters = filters;
    this.sort = sort;
    this.limit = limit;
    this.cursor = cursor;
  }

  /**
   * Creates an empty query bound to an adapter executor.
   *
   * @param executor adapter operation that executes validated query requests
   * @param <T> result document type
   * @return an empty query
   * @throws NullPointerException if {@code executor} is {@code null}
   */
  public static <T> Query<T> create(Executor<T> executor) {
    return new Query<>(Objects.requireNonNull(executor, "executor"), List.of(), null, null, null);
  }

  /**
   * Selects an explicitly indexed field for a comparison.
   *
   * @param indexedField non-blank indexed field name
   * @return the comparison step
   * @throws NullPointerException if {@code indexedField} is {@code null}
   * @throws IllegalArgumentException if {@code indexedField} is blank
   */
  public Comparison<T> where(String indexedField) {
    return new ComparisonStep<>(this, requireText(indexedField, "indexedField"));
  }

  /**
   * Sorts results by one explicitly indexed sortable field.
   *
   * @param indexedField non-blank indexed field name
   * @param direction sort direction
   * @return a new query containing the sort
   * @throws IllegalStateException if a sort is already configured
   */
  public Query<T> sortBy(String indexedField, SortDirection direction) {
    if (sort != null) {
      throw new IllegalStateException("sort is already configured");
    }
    return copy(
        filters,
        new Request.Sort(
            requireText(indexedField, "indexedField"),
            Objects.requireNonNull(direction, "direction")),
        limit,
        cursor);
  }

  /**
   * Sets the required maximum number of documents returned by the query.
   *
   * @param limit positive result limit
   * @return a new query containing the limit
   * @throws IllegalArgumentException if {@code limit} is not positive
   * @throws IllegalStateException if a limit is already configured
   */
  public Query<T> limit(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be greater than zero");
    }
    if (this.limit != null) {
      throw new IllegalStateException("limit is already configured");
    }
    return copy(filters, sort, limit, cursor);
  }

  /**
   * Continues the query after an opaque cursor returned by a previous page.
   *
   * @param cursor non-blank opaque cursor
   * @return a new query containing the cursor
   * @throws IllegalStateException if a cursor is already configured
   */
  public Query<T> after(String cursor) {
    if (this.cursor != null) {
      throw new IllegalStateException("cursor is already configured");
    }
    return copy(filters, sort, limit, requireText(cursor, "cursor"));
  }

  /**
   * Executes this query.
   *
   * @return a non-null result page
   * @throws IllegalStateException if no result limit has been configured
   * @throws NullPointerException if the adapter returns {@code null}
   */
  public Page<T> fetch() {
    if (limit == null) {
      throw new IllegalStateException("limit must be configured before fetch");
    }
    Request request =
        new Request(filters, Optional.ofNullable(sort), limit, Optional.ofNullable(cursor));
    return Objects.requireNonNull(executor.execute(request), "executor returned null");
  }

  private Query<T> compare(String indexedField, Request.ComparisonOperator operator, Object value) {
    Objects.requireNonNull(value, "value");
    List<Request.Filter> updatedFilters = new ArrayList<>(filters);
    updatedFilters.add(new Request.Filter(indexedField, operator, value));
    return copy(List.copyOf(updatedFilters), sort, limit, cursor);
  }

  private Query<T> copy(
      List<Request.Filter> filters,
      Request.@Nullable Sort sort,
      @Nullable Integer limit,
      @Nullable String cursor) {
    return new Query<>(executor, filters, sort, limit, cursor);
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  /**
   * Comparison operations supported by the restricted query model.
   *
   * @param <T> result document type
   */
  public interface Comparison<T> {

    /**
     * Compares for equality.
     *
     * @param value non-null comparison value
     * @return a new query containing the comparison
     */
    Query<T> eq(Object value);

    /**
     * Compares for inequality.
     *
     * @param value non-null comparison value
     * @return a new query containing the comparison
     */
    Query<T> notEq(Object value);

    /**
     * Compares for greater-than ordering.
     *
     * @param value non-null comparison value
     * @return a new query containing the comparison
     */
    Query<T> gt(Object value);

    /**
     * Compares for greater-than-or-equal ordering.
     *
     * @param value non-null comparison value
     * @return a new query containing the comparison
     */
    Query<T> gte(Object value);

    /**
     * Compares for less-than ordering.
     *
     * @param value non-null comparison value
     * @return a new query containing the comparison
     */
    Query<T> lt(Object value);

    /**
     * Compares for less-than-or-equal ordering.
     *
     * @param value non-null comparison value
     * @return a new query containing the comparison
     */
    Query<T> lte(Object value);
  }

  /**
   * Adapter contract for executing an immutable query request.
   *
   * @param <T> result document type
   */
  @FunctionalInterface
  public interface Executor<T> {

    /**
     * Executes a query request.
     *
     * @param request immutable query request
     * @return a non-null result page
     */
    Page<T> execute(Request request);
  }

  /**
   * Immutable, storage-neutral query request passed to an adapter.
   *
   * @param filters AND-combined comparisons
   * @param sort optional single-field sort
   * @param limit positive maximum result count
   * @param cursor optional opaque continuation cursor
   */
  public record Request(
      List<Filter> filters, Optional<Sort> sort, int limit, Optional<String> cursor) {

    /** Creates a validated request with defensive copies. */
    public Request {
      filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
      Objects.requireNonNull(sort, "sort");
      if (limit <= 0) {
        throw new IllegalArgumentException("limit must be greater than zero");
      }
      Objects.requireNonNull(cursor, "cursor");
      cursor.ifPresent(value -> requireText(value, "cursor"));
    }

    /**
     * One comparison against an explicitly indexed field.
     *
     * @param indexedField indexed field name
     * @param operator comparison operator
     * @param value non-null comparison value
     */
    public record Filter(String indexedField, ComparisonOperator operator, Object value) {

      /** Creates a validated filter. */
      public Filter {
        indexedField = requireText(indexedField, "indexedField");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(value, "value");
      }
    }

    /**
     * One sort against an explicitly indexed sortable field.
     *
     * @param indexedField indexed field name
     * @param direction sort direction
     */
    public record Sort(String indexedField, SortDirection direction) {

      /** Creates a validated sort. */
      public Sort {
        indexedField = requireText(indexedField, "indexedField");
        Objects.requireNonNull(direction, "direction");
      }
    }

    /** Comparison operators representable by the public query API. */
    public enum ComparisonOperator {
      /** Matches values that are equal to the comparison value. */
      EQUAL,

      /** Matches values that are not equal to the comparison value. */
      NOT_EQUAL,

      /** Matches values greater than the comparison value. */
      GREATER_THAN,

      /** Matches values greater than or equal to the comparison value. */
      GREATER_THAN_OR_EQUAL,

      /** Matches values less than the comparison value. */
      LESS_THAN,

      /** Matches values less than or equal to the comparison value. */
      LESS_THAN_OR_EQUAL
    }
  }

  private static final class ComparisonStep<T> implements Comparison<T> {

    private final Query<T> query;
    private final String indexedField;

    private ComparisonStep(Query<T> query, String indexedField) {
      this.query = query;
      this.indexedField = indexedField;
    }

    @Override
    public Query<T> eq(Object value) {
      return query.compare(indexedField, Request.ComparisonOperator.EQUAL, value);
    }

    @Override
    public Query<T> notEq(Object value) {
      return query.compare(indexedField, Request.ComparisonOperator.NOT_EQUAL, value);
    }

    @Override
    public Query<T> gt(Object value) {
      return query.compare(indexedField, Request.ComparisonOperator.GREATER_THAN, value);
    }

    @Override
    public Query<T> gte(Object value) {
      return query.compare(indexedField, Request.ComparisonOperator.GREATER_THAN_OR_EQUAL, value);
    }

    @Override
    public Query<T> lt(Object value) {
      return query.compare(indexedField, Request.ComparisonOperator.LESS_THAN, value);
    }

    @Override
    public Query<T> lte(Object value) {
      return query.compare(indexedField, Request.ComparisonOperator.LESS_THAN_OR_EQUAL, value);
    }
  }
}
