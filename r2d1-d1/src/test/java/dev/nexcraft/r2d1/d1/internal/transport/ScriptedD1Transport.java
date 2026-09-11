package dev.nexcraft.r2d1.d1.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.d1.internal.sql.D1Result;
import dev.nexcraft.r2d1.d1.internal.sql.D1Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class ScriptedD1Transport implements D1Transport {

  private final Deque<Step> steps = new ArrayDeque<>();
  private final List<D1Statement> statements = new ArrayList<>();

  public void expect(String sql, List<Map<String, Object>> rows) {
    steps.addLast(new Step(sql, CompletableFuture.completedFuture(new D1Result(rows, 0))));
  }

  public void expect(String sql) {
    expect(sql, List.of());
  }

  public void expectFailure(String sql, RuntimeException failure) {
    steps.addLast(new Step(sql, CompletableFuture.failedFuture(failure)));
  }

  public List<D1Statement> statements() {
    return List.copyOf(statements);
  }

  public void assertExhausted() {
    assertThat(steps).isEmpty();
  }

  @Override
  public CompletionStage<D1Result> execute(D1Statement statement) {
    statements.add(statement);
    Step step = steps.removeFirst();
    assertThat(statement.sql()).isEqualTo(step.sql());
    return step.result();
  }

  private record Step(String sql, CompletionStage<D1Result> result) {}
}
