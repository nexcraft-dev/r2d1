package dev.nexcraft.r2d1.spi;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import dev.nexcraft.r2d1.SortDirection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Technology-neutral query against derived index data.
 *
 * <p>Filters are combined with logical AND. A query has at most one indexed-field sort and uses a
 * structured keyset cursor rather than offsets. Translation from the public query request into this
 * typed storage request belongs to future orchestration code.
 *
 * @param collection non-blank collection to query
 * @param filters AND-combined indexed-field comparisons
 * @param sort optional single-field sort
 * @param limit positive maximum number of document keys to return
 * @param cursor optional structured continuation cursor
 */
public record IndexQuery(
    String collection,
    List<Filter> filters,
    Optional<Sort> sort,
    int limit,
    Optional<IndexCursor> cursor) {

  /** Creates a validated query with defensive copies. */
  public IndexQuery {
    SpiValidation.requireText(collection, "collection");
    filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
    Objects.requireNonNull(sort, "sort");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be greater than zero");
    }
    Objects.requireNonNull(cursor, "cursor");
    cursor.ifPresent(value -> validateCursor(collection, sort.isPresent(), value));
  }

  private static void validateCursor(String collection, boolean sorted, IndexCursor cursor) {
    if (!collection.equals(cursor.lastDocumentKey().collection())) {
      throw new IllegalArgumentException("cursor collection must match query collection");
    }
    if (sorted && cursor.sortValue().isEmpty()) {
      throw new IllegalArgumentException("sorted query cursor must include a sort value");
    }
    if (!sorted && cursor.sortValue().isPresent()) {
      throw new IllegalArgumentException("unsorted query cursor must not include a sort value");
    }
  }

  /**
   * One comparison against an explicitly indexed field.
   *
   * @param indexedField non-blank indexed field name
   * @param operator comparison operator from the public query language
   * @param value typed non-null index value
   */
  public record Filter(String indexedField, ComparisonOperator operator, IndexValue value) {

    /** Creates a validated index filter. */
    public Filter {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(value, "value");
      indexedField = SpiValidation.requireText(indexedField, "indexedField");
    }
  }

  /**
   * One sort against an explicitly indexed field.
   *
   * @param indexedField non-blank indexed field name
   * @param direction sort direction from the public query language
   */
  public record Sort(String indexedField, SortDirection direction) {

    /** Creates a validated index sort. */
    public Sort {
      indexedField = SpiValidation.requireText(indexedField, "indexedField");
      Objects.requireNonNull(direction, "direction");
    }
  }
}
