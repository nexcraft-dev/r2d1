package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class QueryTest {

  @Test
  void buildsAndExecutesARestrictedQuery() {
    AtomicReference<Query.Request> captured = new AtomicReference<>();
    Page<String> expectedPage = new Page<>(List.of("document"), "next");
    Query<String> query =
        Query.create(
            request -> {
              captured.set(request);
              return expectedPage;
            });

    Page<String> result =
        query
            .where("country")
            .eq("NZ")
            .where("createdAt")
            .gte(10)
            .sortBy("createdAt", SortDirection.DESC)
            .limit(20)
            .after("cursor")
            .fetch();

    assertThat(result).isSameAs(expectedPage);
    assertThat(captured.get().filters())
        .containsExactly(
            new Query.Request.Filter("country", ComparisonOperator.EQUAL, "NZ"),
            new Query.Request.Filter("createdAt", ComparisonOperator.GREATER_THAN_OR_EQUAL, 10));
    assertThat(captured.get().sort())
        .contains(new Query.Request.Sort("createdAt", SortDirection.DESC));
    assertThat(captured.get().limit()).isEqualTo(20);
    assertThat(captured.get().cursor()).contains("cursor");
  }

  @Test
  void mapsEverySupportedComparisonOperator() {
    assertOperator(comparison -> comparison.eq(1), ComparisonOperator.EQUAL);
    assertOperator(comparison -> comparison.notEq(1), ComparisonOperator.NOT_EQUAL);
    assertOperator(comparison -> comparison.gt(1), ComparisonOperator.GREATER_THAN);
    assertOperator(comparison -> comparison.gte(1), ComparisonOperator.GREATER_THAN_OR_EQUAL);
    assertOperator(comparison -> comparison.lt(1), ComparisonOperator.LESS_THAN);
    assertOperator(comparison -> comparison.lte(1), ComparisonOperator.LESS_THAN_OR_EQUAL);
  }

  @Test
  void keepsQueryInstancesImmutable() {
    Query<String> base = successfulQuery();
    Query<String> limited = base.limit(10);

    assertThatThrownBy(base::fetch)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("limit must be configured before fetch");
    assertThat(limited.fetch().items()).containsExactly("document");
  }

  @Test
  void requiresALimitBeforeFetch() {
    assertThatThrownBy(() -> successfulQuery().fetch())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("limit must be configured before fetch");
  }

  @Test
  void rejectsInvalidLimits() {
    Query<String> query = successfulQuery();

    assertThatThrownBy(() -> query.limit(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must be greater than zero");
    assertThatThrownBy(() -> query.limit(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must be greater than zero");
  }

  @Test
  void rejectsBlankFieldNamesAndCursors() {
    Query<String> query = successfulQuery();

    assertThatThrownBy(() -> query.where(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("indexedField must not be blank");
    assertThatThrownBy(() -> query.sortBy("", SortDirection.ASC))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("indexedField must not be blank");
    assertThatThrownBy(() -> query.after("\t"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("cursor must not be blank");
  }

  @Test
  void rejectsNullQueryInputs() {
    Query<String> query = successfulQuery();

    assertThatNullPointerException().isThrownBy(() -> Query.create(null)).withMessage("executor");
    assertThatNullPointerException()
        .isThrownBy(() -> query.where(null))
        .withMessage("indexedField");
    assertThatNullPointerException()
        .isThrownBy(() -> query.where("country").eq(null))
        .withMessage("value");
    assertThatNullPointerException()
        .isThrownBy(() -> query.sortBy("country", null))
        .withMessage("direction");
    assertThatNullPointerException().isThrownBy(() -> query.after(null)).withMessage("cursor");
  }

  @Test
  void rejectsDuplicateSingleValueOptions() {
    Query<String> query = successfulQuery();

    assertThatThrownBy(
            () ->
                query.sortBy("createdAt", SortDirection.ASC).sortBy("country", SortDirection.DESC))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("sort is already configured");
    assertThatThrownBy(() -> query.limit(10).limit(20))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("limit is already configured");
    assertThatThrownBy(() -> query.after("first").after("second"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("cursor is already configured");
  }

  @Test
  void rejectsANullPageFromTheExecutor() {
    Query<String> query = Query.<String>create(request -> null).limit(1);

    assertThatNullPointerException().isThrownBy(query::fetch).withMessage("executor returned null");
  }

  @Test
  void queryRequestsDefensivelyCopyFilters() {
    List<Query.Request.Filter> filters = new ArrayList<>();
    filters.add(new Query.Request.Filter("country", ComparisonOperator.EQUAL, "NZ"));

    Query.Request request = new Query.Request(filters, Optional.empty(), 10, Optional.empty());
    filters.clear();

    assertThat(request.filters()).hasSize(1);
    assertThatThrownBy(() -> request.filters().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static Query<String> successfulQuery() {
    return Query.create(request -> new Page<>(List.of("document"), null));
  }

  private static void assertOperator(
      Function<Query.Comparison<String>, Query<String>> operation,
      ComparisonOperator expectedOperator) {
    AtomicReference<Query.Request> captured = new AtomicReference<>();
    Query<String> query =
        Query.create(
            request -> {
              captured.set(request);
              return new Page<>(List.of(), null);
            });

    operation.apply(query.where("field")).limit(1).fetch();

    assertThat(captured.get().filters())
        .singleElement()
        .extracting(Query.Request.Filter::operator)
        .isEqualTo(expectedOperator);
  }
}
