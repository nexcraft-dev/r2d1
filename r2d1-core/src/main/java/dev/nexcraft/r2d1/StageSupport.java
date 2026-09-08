package dev.nexcraft.r2d1;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Minimal stage composition and synchronous-boundary support. */
final class StageSupport {

  private StageSupport() {}

  static <T extends @Nullable Object> CompletionStage<T> invoke(
      Supplier<? extends CompletionStage<T>> operation) {
    try {
      return Objects.requireNonNull(operation.get(), "operation returned a null stage");
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  static <T extends @Nullable Object> CompletionStage<T> mapFailure(
      CompletionStage<T> stage, Function<Throwable, Throwable> mapper) {
    CompletableFuture<T> result = new CompletableFuture<>();
    stage.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            result.complete(value);
            return;
          }
          try {
            result.completeExceptionally(mapper.apply(unwrap(failure)));
          } catch (RuntimeException mappingFailure) {
            result.completeExceptionally(mappingFailure);
          }
        });
    return result;
  }

  static <T extends @Nullable Object> T await(CompletionStage<T> stage) {
    Objects.requireNonNull(stage, "stage");
    try {
      return stage.toCompletableFuture().get();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new PersistenceException("Interrupted while waiting for persistence", failure);
    } catch (ExecutionException failure) {
      throw propagate(unwrap(failure));
    }
  }

  static Throwable unwrap(Throwable failure) {
    Throwable current = Objects.requireNonNull(failure, "failure");
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static RuntimeException propagate(Throwable failure) {
    if (failure instanceof RuntimeException runtimeFailure) {
      return runtimeFailure;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    return new PersistenceException("Asynchronous persistence operation failed", failure);
  }
}
