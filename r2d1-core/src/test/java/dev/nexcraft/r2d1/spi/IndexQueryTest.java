package dev.nexcraft.r2d1.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import dev.nexcraft.r2d1.SortDirection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IndexQueryTest {

  private static final DocumentKey KEY = new DocumentKey("users", "user-123");
  private static final IndexValue VALUE = new IndexValue.StringValue("NZ");

  @Test
  void defensivelyCopiesFilters() {
    List<IndexQuery.Filter> filters = new ArrayList<>();
    filters.add(new IndexQuery.Filter("country", ComparisonOperator.EQUAL, VALUE));

    IndexQuery query = query(filters, Optional.empty(), Optional.empty());
    filters.clear();

    assertThat(query.filters()).hasSize(1);
    assertThatThrownBy(() -> query.filters().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void supportsEveryPublicComparisonOperator() {
    List<ComparisonOperator> operators =
        List.of(
            ComparisonOperator.EQUAL,
            ComparisonOperator.NOT_EQUAL,
            ComparisonOperator.GREATER_THAN,
            ComparisonOperator.GREATER_THAN_OR_EQUAL,
            ComparisonOperator.LESS_THAN,
            ComparisonOperator.LESS_THAN_OR_EQUAL);
    List<IndexQuery.Filter> filters =
        operators.stream()
            .map(operator -> new IndexQuery.Filter("field", operator, VALUE))
            .toList();

    IndexQuery query = query(filters, Optional.empty(), Optional.empty());

    assertThat(query.filters())
        .extracting(IndexQuery.Filter::operator)
        .containsExactlyElementsOf(operators);
    assertThat(ComparisonOperator.values()).containsExactlyElementsOf(operators);
  }

  @Test
  void acceptsConsistentSortedAndUnsortedCursors() {
    IndexCursor unsortedCursor = new IndexCursor(KEY, Optional.empty());
    IndexCursor sortedCursor = new IndexCursor(KEY, Optional.of(VALUE));
    IndexQuery.Sort sort = new IndexQuery.Sort("country", SortDirection.ASC);

    assertThat(query(List.of(), Optional.empty(), Optional.of(unsortedCursor)).cursor())
        .contains(unsortedCursor);
    assertThat(query(List.of(), Optional.of(sort), Optional.of(sortedCursor)).cursor())
        .contains(sortedCursor);
  }

  @Test
  void rejectsACursorFromAnotherCollection() {
    IndexCursor cursor = new IndexCursor(new DocumentKey("orders", "order-123"), Optional.empty());

    assertThatThrownBy(() -> query(List.of(), Optional.empty(), Optional.of(cursor)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("cursor collection must match query collection");
  }

  @Test
  void rejectsACursorWithoutTheSortedFieldValueForASortedQuery() {
    IndexCursor cursor = new IndexCursor(KEY, Optional.empty());
    IndexQuery.Sort sort = new IndexQuery.Sort("country", SortDirection.ASC);

    assertThatThrownBy(() -> query(List.of(), Optional.of(sort), Optional.of(cursor)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sorted query cursor must include a sort value");
  }

  @Test
  void rejectsASortValueForAnUnsortedQuery() {
    IndexCursor cursor = new IndexCursor(KEY, Optional.of(VALUE));

    assertThatThrownBy(() -> query(List.of(), Optional.empty(), Optional.of(cursor)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsorted query cursor must not include a sort value");
  }

  @Test
  void rejectsNonPositiveLimits() {
    assertThatThrownBy(
            () -> new IndexQuery("users", List.of(), Optional.empty(), 0, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must be greater than zero");
    assertThatThrownBy(
            () -> new IndexQuery("users", List.of(), Optional.empty(), -1, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must be greater than zero");
  }

  @Test
  void rejectsBlankCollectionAndFieldNames() {
    assertThatThrownBy(() -> new IndexQuery(" ", List.of(), Optional.empty(), 1, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("collection must not be blank");
    assertThatThrownBy(() -> new IndexQuery.Filter("\t", ComparisonOperator.EQUAL, VALUE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("indexedField must not be blank");
    assertThatThrownBy(() -> new IndexQuery.Sort("", SortDirection.ASC))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("indexedField must not be blank");
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullComponents() {
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexQuery(null, List.of(), Optional.empty(), 1, Optional.empty()))
        .withMessage("collection");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexQuery("users", null, Optional.empty(), 1, Optional.empty()))
        .withMessage("filters");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexQuery("users", List.of(), null, 1, Optional.empty()))
        .withMessage("sort");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexQuery("users", List.of(), Optional.empty(), 1, null))
        .withMessage("cursor");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexQuery.Filter("field", null, VALUE))
        .withMessage("operator");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexQuery.Filter("field", ComparisonOperator.EQUAL, null))
        .withMessage("value");
    assertThatNullPointerException()
        .isThrownBy(() -> new IndexQuery.Sort("field", null))
        .withMessage("direction");
  }

  private static IndexQuery query(
      List<IndexQuery.Filter> filters,
      Optional<IndexQuery.Sort> sort,
      Optional<IndexCursor> cursor) {
    return new IndexQuery("users", filters, sort, 20, cursor);
  }
}
