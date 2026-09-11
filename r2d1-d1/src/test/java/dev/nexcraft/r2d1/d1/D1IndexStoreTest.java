package dev.nexcraft.r2d1.d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Index;
import dev.nexcraft.r2d1.d1.internal.sql.D1Parameter;
import dev.nexcraft.r2d1.d1.internal.sql.D1Result;
import dev.nexcraft.r2d1.d1.internal.transport.D1Transport;
import dev.nexcraft.r2d1.d1.internal.transport.ScriptedD1Transport;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.IndexEntry;
import dev.nexcraft.r2d1.spi.IndexPage;
import dev.nexcraft.r2d1.spi.IndexQuery;
import dev.nexcraft.r2d1.spi.IndexValue;
import dev.nexcraft.r2d1.spi.StorageException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class D1IndexStoreTest {

  @Test
  void requiresInitializationBeforeEveryIndexOperation() {
    D1IndexStore store = new D1IndexStore(statement -> CompletableFuture.completedFuture(empty()));

    assertThat(completedFailure(store.upsert(entry("user-1"))))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("D1 collection is not initialized: users");
    assertThat(completedFailure(store.clear("users")))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("D1 collection is not initialized: users");
    assertThat(completedFailure(store.query(query())))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("D1 collection is not initialized: users");
    assertThat(completedFailure(store.delete(new DocumentKey("users", "user-1"))))
        .isInstanceOf(StorageException.Operation.class)
        .hasMessage("D1 collection is not initialized: users");
  }

  @Test
  void initializesThenExecutesUpsertQueryClearAndIdempotentDelete() {
    ScriptedD1Transport transport = new ScriptedD1Transport();
    expectCompatibleSchema(transport);
    transport.expect(
        "INSERT INTO \"users\" (\"document_id\", \"country\") VALUES (?1, ?2) "
            + "ON CONFLICT(\"document_id\") DO UPDATE SET \"country\" = excluded.\"country\"");
    transport.expect(
        "SELECT \"document_id\" FROM \"users\" ORDER BY \"document_id\" ASC LIMIT ?1",
        List.of(
            Map.of("document_id", "a"), Map.of("document_id", "b"), Map.of("document_id", "c")));
    transport.expect("DELETE FROM \"users\"");
    transport.expect("DELETE FROM \"users\" WHERE \"document_id\" = ?1");
    D1IndexStore store = new D1IndexStore(transport);

    store.initialize(User.class).toCompletableFuture().join();
    store.upsert(entry("user-1")).toCompletableFuture().join();
    IndexPage page = store.query(query()).toCompletableFuture().join();
    store.clear("users").toCompletableFuture().join();
    store.delete(new DocumentKey("users", "absent")).toCompletableFuture().join();

    assertThat(page.documentKeys())
        .containsExactly(new DocumentKey("users", "a"), new DocumentKey("users", "b"));
    assertThat(page.nextCursor()).isPresent();
    assertThat(transport.statements().get(4).parameters())
        .containsExactly(
            new D1Parameter.TextParameter("user-1"), new D1Parameter.TextParameter("NZ"));
    assertThat(transport.statements().get(5).parameters())
        .containsExactly(new D1Parameter.IntegerParameter(3));
    assertThat(transport.statements().get(6).parameters()).isEmpty();
    assertThat(transport.statements().get(6).sql()).doesNotContain("DROP");
    transport.assertExhausted();
  }

  @Test
  void propagatesTheOriginalClearFailure() {
    ScriptedD1Transport transport = new ScriptedD1Transport();
    expectCompatibleSchema(transport);
    StorageException failure = new StorageException.Unavailable("D1 unavailable");
    transport.expectFailure("DELETE FROM \"users\"", failure);
    D1IndexStore store = new D1IndexStore(transport);

    store.initialize(User.class).toCompletableFuture().join();

    assertThat(completedFailure(store.clear("users"))).isSameAs(failure);
    transport.assertExhausted();
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void validatesClearCollection() {
    D1IndexStore store = new D1IndexStore(statement -> CompletableFuture.completedFuture(empty()));

    assertThatNullPointerException().isThrownBy(() -> store.clear(null)).withMessage("collection");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.clear(" "))
        .withMessage("collection must not be blank");
  }

  @Test
  void sharesOneInFlightSchemaInitializationAndCachesItsFailure() {
    AtomicInteger calls = new AtomicInteger();
    CompletableFuture<D1Result> pending = new CompletableFuture<>();
    D1Transport transport =
        statement -> {
          calls.incrementAndGet();
          return pending;
        };
    D1IndexStore store = new D1IndexStore(transport);

    CompletionStage<@Nullable Void> first = store.initialize(User.class);
    CompletionStage<@Nullable Void> second = store.initialize(User.class);

    assertThat(second).isSameAs(first);
    assertThat(calls).hasValue(1);

    StorageException failure = new StorageException.Unavailable("D1 unavailable");
    pending.completeExceptionally(failure);
    assertThat(completedFailure(first)).isSameAs(failure);
    assertThat(completedFailure(store.initialize(User.class))).isSameAs(failure);
    assertThat(calls).hasValue(1);
  }

  @Test
  void rejectsDifferentMetadataForTheSameCollection() {
    CompletableFuture<D1Result> pending = new CompletableFuture<>();
    D1IndexStore store = new D1IndexStore(statement -> pending);
    store.initialize(User.class);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.initialize(IncompatibleUser.class))
        .withMessage("collection is already initialized with different metadata: users");
  }

  private static void expectCompatibleSchema(ScriptedD1Transport transport) {
    transport.expect(
        "PRAGMA table_info(\"users\")",
        List.of(
            Map.of("name", "document_id", "type", "TEXT", "notnull", 1L, "pk", 1L),
            Map.of("name", "country", "type", "TEXT", "notnull", 1L, "pk", 0L)));
    List<Map<String, Object>> indexes = List.of(Map.of("name", "idx_users_country"));
    transport.expect("PRAGMA index_list(\"users\")", indexes);
    transport.expect("PRAGMA index_list(\"users\")", indexes);
    transport.expect(
        "PRAGMA index_info(\"idx_users_country\")", List.of(Map.of("name", "country")));
  }

  private static IndexEntry entry(String id) {
    return new IndexEntry(
        new DocumentKey("users", id), Map.of("country", new IndexValue.StringValue("NZ")));
  }

  private static IndexQuery query() {
    return new IndexQuery("users", List.of(), Optional.empty(), 2, Optional.empty());
  }

  private static D1Result empty() {
    return new D1Result(List.of(), 0);
  }

  private static Throwable completedFailure(CompletionStage<?> stage) {
    try {
      stage.toCompletableFuture().join();
      throw new AssertionError("stage completed successfully");
    } catch (CompletionException failure) {
      return failure.getCause();
    }
  }

  @Document("users")
  private static final class User {
    @Index private String country;
  }

  @Document("users")
  private static final class IncompatibleUser {
    @Index private Long createdAt;
  }
}
