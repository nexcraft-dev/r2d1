package dev.nexcraft.r2d1.d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.nexcraft.r2d1.Query.Request.ComparisonOperator;
import dev.nexcraft.r2d1.SortDirection;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexCursor;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class D1SqlCompilerTest {

  private static final D1CollectionMetadata METADATA =
      new D1CollectionMetadata(
          D1SqlCompilerTest.class,
          "users",
          List.of(
              new D1IndexedField("active", D1ValueType.BOOLEAN),
              new D1IndexedField("country", D1ValueType.STRING),
              new D1IndexedField("createdAt", D1ValueType.LONG),
              new D1IndexedField("score", D1ValueType.DOUBLE)));
  private static final D1SqlCompiler COMPILER = new D1SqlCompiler();

  @Test
  void compilesDeterministicUpsertAndDeleteStatements() {
    Map<String, IndexValue> values = new LinkedHashMap<>();
    values.put("score", new IndexValue.DoubleValue(4.5));
    values.put("createdAt", new IndexValue.LongValue(42));
    values.put("country", new IndexValue.StringValue("NZ"));
    values.put("active", new IndexValue.BooleanValue(true));

    D1Statement upsert =
        COMPILER.upsert(METADATA, new IndexEntry(new DocumentKey("users", "user-1"), values));
    D1Statement delete = COMPILER.delete(METADATA, new DocumentKey("users", "user-1"));
    D1Statement clear = COMPILER.clear(METADATA);

    assertThat(upsert.sql())
        .isEqualTo(
            "INSERT INTO \"users\" (\"document_id\", \"active\", \"country\", "
                + "\"createdAt\", \"score\") VALUES (?1, ?2, ?3, ?4, ?5) "
                + "ON CONFLICT(\"document_id\") DO UPDATE SET \"active\" = excluded.\"active\", "
                + "\"country\" = excluded.\"country\", \"createdAt\" = excluded.\"createdAt\", "
                + "\"score\" = excluded.\"score\"");
    assertThat(upsert.parameters())
        .containsExactly(
            new D1Parameter.TextParameter("user-1"),
            new D1Parameter.IntegerParameter(1),
            new D1Parameter.TextParameter("NZ"),
            new D1Parameter.IntegerParameter(42),
            new D1Parameter.RealParameter(4.5));
    assertThat(delete.sql()).isEqualTo("DELETE FROM \"users\" WHERE \"document_id\" = ?1");
    assertThat(delete.parameters()).containsExactly(new D1Parameter.TextParameter("user-1"));
    assertThat(clear.sql()).isEqualTo("DELETE FROM \"users\"").doesNotContain("DROP");
    assertThat(clear.parameters()).isEmpty();
  }

  @Test
  void usesDoNothingWhenACollectionHasNoIndexes() {
    D1CollectionMetadata metadata =
        new D1CollectionMetadata(D1SqlCompilerTest.class, "events", List.of());

    D1Statement statement =
        COMPILER.upsert(metadata, new IndexEntry(new DocumentKey("events", "event-1"), Map.of()));

    assertThat(statement.sql())
        .isEqualTo(
            "INSERT INTO \"events\" (\"document_id\") VALUES (?1) "
                + "ON CONFLICT(\"document_id\") DO NOTHING");
  }

  @Test
  void compilesEveryComparisonAsAndFiltersInParameterOrder() {
    List<IndexQuery.Filter> filters =
        List.of(
            filter("country", ComparisonOperator.EQUAL, new IndexValue.StringValue("NZ")),
            filter("createdAt", ComparisonOperator.NOT_EQUAL, new IndexValue.LongValue(1)),
            filter("score", ComparisonOperator.GREATER_THAN, new IndexValue.DoubleValue(2.0)),
            filter(
                "createdAt", ComparisonOperator.GREATER_THAN_OR_EQUAL, new IndexValue.LongValue(3)),
            filter("score", ComparisonOperator.LESS_THAN, new IndexValue.DoubleValue(4.0)),
            filter(
                "createdAt", ComparisonOperator.LESS_THAN_OR_EQUAL, new IndexValue.LongValue(5)));

    D1SqlCompiler.CompiledQuery compiled =
        COMPILER.query(
            METADATA, new IndexQuery("users", filters, Optional.empty(), 10, Optional.empty()));

    assertThat(compiled.statement().sql())
        .isEqualTo(
            "SELECT \"document_id\" FROM \"users\" WHERE \"country\" = ?1 AND "
                + "\"createdAt\" <> ?2 AND \"score\" > ?3 AND \"createdAt\" >= ?4 AND "
                + "\"score\" < ?5 AND \"createdAt\" <= ?6 "
                + "ORDER BY \"document_id\" ASC LIMIT ?7");
    assertThat(compiled.statement().parameters())
        .containsExactly(
            new D1Parameter.TextParameter("NZ"),
            new D1Parameter.IntegerParameter(1),
            new D1Parameter.RealParameter(2.0),
            new D1Parameter.IntegerParameter(3),
            new D1Parameter.RealParameter(4.0),
            new D1Parameter.IntegerParameter(5),
            new D1Parameter.IntegerParameter(11));
  }

  @Test
  void supportsEveryComparisonForStringLongAndDoubleAndEqualityForBoolean() {
    assertAllComparisons("country", new IndexValue.StringValue("NZ"));
    assertAllComparisons("createdAt", new IndexValue.LongValue(42));
    assertAllComparisons("score", new IndexValue.DoubleValue(4.5));

    for (ComparisonOperator operator :
        List.of(ComparisonOperator.EQUAL, ComparisonOperator.NOT_EQUAL)) {
      D1SqlCompiler.CompiledQuery compiled =
          compileFilter("active", operator, new IndexValue.BooleanValue(true));

      assertThat(compiled.statement().sql())
          .contains("\"active\" " + operatorSql(operator) + " ?1");
      assertThat(compiled.statement().parameters().get(0))
          .isEqualTo(new D1Parameter.IntegerParameter(1));
    }
  }

  @Test
  void compilesAscendingAndDescendingKeysetCursorsWithStableTieBreaker() {
    IndexCursor cursor =
        new IndexCursor(
            new DocumentKey("users", "user-9"), Optional.of(new IndexValue.LongValue(100)));

    D1SqlCompiler.CompiledQuery ascending =
        COMPILER.query(METADATA, sortedQuery(SortDirection.ASC, cursor));
    D1SqlCompiler.CompiledQuery descending =
        COMPILER.query(METADATA, sortedQuery(SortDirection.DESC, cursor));

    assertThat(ascending.statement().sql())
        .isEqualTo(
            "SELECT \"document_id\", \"createdAt\" FROM \"users\" WHERE "
                + "(\"createdAt\" > ?1 OR (\"createdAt\" = ?1 AND \"document_id\" > ?2)) "
                + "ORDER BY \"createdAt\" ASC, \"document_id\" ASC LIMIT ?3");
    assertThat(descending.statement().sql())
        .isEqualTo(
            "SELECT \"document_id\", \"createdAt\" FROM \"users\" WHERE "
                + "(\"createdAt\" < ?1 OR (\"createdAt\" = ?1 AND \"document_id\" < ?2)) "
                + "ORDER BY \"createdAt\" DESC, \"document_id\" DESC LIMIT ?3");
    assertThat(ascending.statement().parameters())
        .containsExactly(
            new D1Parameter.IntegerParameter(100),
            new D1Parameter.TextParameter("user-9"),
            new D1Parameter.IntegerParameter(21));
  }

  @Test
  void compilesUnsortedDocumentIdCursor() {
    IndexCursor cursor = new IndexCursor(new DocumentKey("users", "user-9"), Optional.empty());

    D1SqlCompiler.CompiledQuery compiled =
        COMPILER.query(
            METADATA,
            new IndexQuery("users", List.of(), Optional.empty(), 20, Optional.of(cursor)));

    assertThat(compiled.statement().sql())
        .isEqualTo(
            "SELECT \"document_id\" FROM \"users\" WHERE \"document_id\" > ?1 "
                + "ORDER BY \"document_id\" ASC LIMIT ?2");
  }

  @Test
  void mapsOneExtraRowToANextCursorWithoutDuplicatingEqualSortValues() {
    D1SqlCompiler.CompiledQuery compiled =
        COMPILER.query(
            METADATA,
            new IndexQuery(
                "users",
                List.of(),
                Optional.of(new IndexQuery.Sort("createdAt", SortDirection.ASC)),
                2,
                Optional.empty()));
    D1Result result =
        new D1Result(
            List.of(
                Map.of("document_id", "a", "createdAt", 7L),
                Map.of("document_id", "b", "createdAt", 7L),
                Map.of("document_id", "c", "createdAt", 7L)),
            0);

    IndexPage page = COMPILER.page(METADATA, compiled, result);

    assertThat(page.documentKeys())
        .containsExactly(new DocumentKey("users", "a"), new DocumentKey("users", "b"));
    assertThat(page.nextCursor())
        .contains(
            new IndexCursor(
                new DocumentKey("users", "b"), Optional.of(new IndexValue.LongValue(7))));
  }

  @Test
  void rejectsUnindexedMismatchedNullOrUnsupportedComparisonsBeforeExecution() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                COMPILER.query(
                    METADATA,
                    new IndexQuery(
                        "users",
                        List.of(
                            filter(
                                "missing",
                                ComparisonOperator.EQUAL,
                                new IndexValue.StringValue("x"))),
                        Optional.empty(),
                        1,
                        Optional.empty())))
        .withMessage("field is not indexed: missing");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                COMPILER.query(
                    METADATA,
                    new IndexQuery(
                        "users",
                        List.of(
                            filter(
                                "createdAt",
                                ComparisonOperator.EQUAL,
                                new IndexValue.DoubleValue(1.0))),
                        Optional.empty(),
                        1,
                        Optional.empty())))
        .withMessageContaining("type does not match");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                COMPILER.query(
                    METADATA,
                    new IndexQuery(
                        "users",
                        List.of(
                            filter(
                                "active",
                                ComparisonOperator.GREATER_THAN,
                                new IndexValue.BooleanValue(true))),
                        Optional.empty(),
                        1,
                        Optional.empty())))
        .withMessageContaining("supports only equality");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                COMPILER.upsert(
                    METADATA,
                    new IndexEntry(
                        new DocumentKey("users", "user-1"),
                        Map.of(
                            "active",
                            new IndexValue.BooleanValue(true),
                            "country",
                            new IndexValue.StringValue("NZ"),
                            "createdAt",
                            new IndexValue.LongValue(1)))))
        .withMessage("indexed field value must not be null or missing: score");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                COMPILER.query(
                    METADATA,
                    new IndexQuery(
                        "users",
                        List.of(
                            filter(
                                "country",
                                ComparisonOperator.EQUAL,
                                new IndexValue.TimestampValue(java.time.Instant.EPOCH))),
                        Optional.empty(),
                        1,
                        Optional.empty())))
        .withMessageContaining("type does not match");
  }

  private static IndexQuery.Filter filter(
      String field, ComparisonOperator operator, IndexValue value) {
    return new IndexQuery.Filter(field, operator, value);
  }

  private static void assertAllComparisons(String field, IndexValue value) {
    for (ComparisonOperator operator : ComparisonOperator.values()) {
      D1SqlCompiler.CompiledQuery compiled = compileFilter(field, operator, value);

      assertThat(compiled.statement().sql())
          .contains("\"" + field + "\" " + operatorSql(operator) + " ?1");
    }
  }

  private static D1SqlCompiler.CompiledQuery compileFilter(
      String field, ComparisonOperator operator, IndexValue value) {
    return COMPILER.query(
        METADATA,
        new IndexQuery(
            "users",
            List.of(filter(field, operator, value)),
            Optional.empty(),
            1,
            Optional.empty()));
  }

  private static String operatorSql(ComparisonOperator operator) {
    return switch (operator) {
      case EQUAL -> "=";
      case NOT_EQUAL -> "<>";
      case GREATER_THAN -> ">";
      case GREATER_THAN_OR_EQUAL -> ">=";
      case LESS_THAN -> "<";
      case LESS_THAN_OR_EQUAL -> "<=";
    };
  }

  private static IndexQuery sortedQuery(SortDirection direction, IndexCursor cursor) {
    return new IndexQuery(
        "users",
        List.of(),
        Optional.of(new IndexQuery.Sort("createdAt", direction)),
        20,
        Optional.of(cursor));
  }
}
