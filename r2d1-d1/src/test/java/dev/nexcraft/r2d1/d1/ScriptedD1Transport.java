package dev.nexcraft.r2d1.d1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ScriptedD1Transport implements D1Transport {

  private final Deque<Step> steps = new ArrayDeque<>();
  private final List<D1Statement> statements = new ArrayList<>();

  void expect(String sql, List<Map<String, Object>> rows) {
    steps.addLast(new Step(sql, CompletableFuture.completedFuture(new D1Result(rows, 0))));
  }

  void expect(String sql) {
    expect(sql, List.of());
  }

  void expectFailure(String sql, RuntimeException failure) {
    steps.addLast(new Step(sql, CompletableFuture.failedFuture(failure)));
  }

  List<D1Statement> statements() {
    return List.copyOf(statements);
  }

  void assertExhausted() {
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
