package dev.nexcraft.r2d1.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.nexcraft.r2d1.spi.StorageException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class JdbcExecutionTest {

  @Test
  void runsWorkOnOwnedBoundedPlatformThreads() {
    CountDownLatch runningStarted = new CountDownLatch(1);
    CountDownLatch releaseRunning = new CountDownLatch(1);
    AtomicBoolean pendingStarted = new AtomicBoolean();
    AtomicReference<Thread> worker = new AtomicReference<>();

    try (JdbcExecution execution = JdbcExecution.create(1, 1)) {
      CompletionStage<String> running =
          execution.execute(
              () -> {
                worker.set(Thread.currentThread());
                assertThat(Thread.currentThread().getName()).startsWith("r2d1-jdbc-");
                assertThat(Thread.currentThread().isVirtual()).isFalse();
                runningStarted.countDown();
                await(releaseRunning);
                return "running";
              });
      await(runningStarted);
      CompletionStage<String> pending =
          execution.execute(
              () -> {
                pendingStarted.set(true);
                return "pending";
              });

      assertThat(pendingStarted).isFalse();
      Throwable rejected = completedFailure(execution.execute(() -> "rejected"));
      assertThat(rejected)
          .isInstanceOf(StorageException.Unavailable.class)
          .hasMessage("JDBC execution capacity is exhausted")
          .hasCauseInstanceOf(RejectedExecutionException.class);

      releaseRunning.countDown();
      assertThat(completedValue(running)).isEqualTo("running");
      assertThat(completedValue(pending)).isEqualTo("pending");
    }
    awaitTermination(worker.get());
  }

  @Test
  void runsWorkOnOwnedVirtualThreadsWhenExplicitlyConfigured() {
    Assumptions.assumeTrue(
        Runtime.version().feature() >= 25, "virtual-thread mode requires Java 25 or newer");
    AtomicReference<Thread> worker = new AtomicReference<>();

    try (JdbcExecution execution =
        JdbcExecution.create(new JdbcExecutionConfig(JdbcExecutionMode.VIRTUAL_THREAD, 1, 1))) {
      assertThat(
              completedValue(
                  execution.execute(
                      () -> {
                        worker.set(Thread.currentThread());
                        return "virtual";
                      })))
          .isEqualTo("virtual");
    }

    assertThat(worker.get().isVirtual()).isTrue();
    assertThat(worker.get().getName()).startsWith("r2d1-jdbc-virtual-");
  }

  @Test
  void rejectsVirtualThreadsOnUnsupportedRuntime() {
    Assumptions.assumeTrue(
        Runtime.version().feature() < 25, "this fail-fast assertion runs on Java 21");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    JdbcExecution.create(
                        new JdbcExecutionConfig(JdbcExecutionMode.VIRTUAL_THREAD, 1, 0))))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("VIRTUAL_THREAD requires Java 25 or newer");
  }

  @Test
  void preservesVirtualThreadConcurrencyAndPendingBounds() {
    Assumptions.assumeTrue(
        Runtime.version().feature() >= 25, "virtual-thread mode requires Java 25 or newer");
    CountDownLatch runningStarted = new CountDownLatch(2);
    CountDownLatch releaseRunning = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    AtomicBoolean pendingStarted = new AtomicBoolean();

    try (JdbcExecution execution =
        JdbcExecution.create(new JdbcExecutionConfig(JdbcExecutionMode.VIRTUAL_THREAD, 2, 2))) {
      CompletionStage<String> first =
          execution.execute(() -> blockingOperation(runningStarted, releaseRunning, active, peak));
      CompletionStage<String> second =
          execution.execute(() -> blockingOperation(runningStarted, releaseRunning, active, peak));
      await(runningStarted);
      CompletionStage<String> third =
          execution.execute(
              () -> {
                pendingStarted.set(true);
                return "pending-1";
              });
      CompletionStage<String> fourth = execution.execute(() -> "pending-2");

      Throwable rejected = completedFailure(execution.execute(() -> "rejected"));
      assertThat(rejected)
          .isInstanceOf(StorageException.Unavailable.class)
          .hasMessage("JDBC execution capacity is exhausted")
          .hasCauseInstanceOf(RejectedExecutionException.class);
      assertThat(pendingStarted).isFalse();

      releaseRunning.countDown();
      assertThat(completedValue(first)).isEqualTo("running");
      assertThat(completedValue(second)).isEqualTo("running");
      assertThat(completedValue(third)).isEqualTo("pending-1");
      assertThat(completedValue(fourth)).isEqualTo("pending-2");
      assertThat(peak).hasValueLessThanOrEqualTo(2);
    }
  }

  @Test
  void completesOperationFailuresWithoutWrappingTheCause() {
    RuntimeException failure = new RuntimeException("operation failure");
    try (JdbcExecution execution = JdbcExecution.create(1, 0)) {
      assertThat(
              completedFailure(
                  execution.execute(
                      () -> {
                        throw failure;
                      })))
          .isSameAs(failure);
    }
  }

  @Test
  void closeFailsPendingAndNewWorkButLetsRunningWorkFinish() {
    CountDownLatch runningStarted = new CountDownLatch(1);
    CountDownLatch releaseRunning = new CountDownLatch(1);
    AtomicBoolean pendingStarted = new AtomicBoolean();
    JdbcExecution execution = JdbcExecution.create(1, 1);
    CompletionStage<String> running =
        execution.execute(
            () -> {
              runningStarted.countDown();
              await(releaseRunning);
              return "finished";
            });
    await(runningStarted);
    CompletionStage<String> pending =
        execution.execute(
            () -> {
              pendingStarted.set(true);
              return "unexpected";
            });

    execution.close();
    execution.close();

    assertThat(completedFailure(pending))
        .isInstanceOf(StorageException.Unavailable.class)
        .hasMessage("JDBC execution is closed");
    assertThat(completedFailure(execution.execute(() -> "unexpected")))
        .isInstanceOf(StorageException.Unavailable.class)
        .hasMessage("JDBC execution is closed");
    assertThat(pendingStarted).isFalse();
    releaseRunning.countDown();
    assertThat(completedValue(running)).isEqualTo("finished");
  }

  @Test
  void doesNotShutDownCallerOwnedExecutor() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      JdbcExecution execution = JdbcExecution.using(executor, 1, 1);

      assertThat(completedValue(execution.execute(() -> "value"))).isEqualTo("value");
      execution.close();

      assertThat(executor.isShutdown()).isFalse();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void convertsDelegateRejectionToExceptionalCompletion() {
    JdbcExecution execution =
        JdbcExecution.using(
            ignored -> {
              throw new RejectedExecutionException("delegate rejected");
            },
            1,
            0);
    try {
      Throwable failure = completedFailure(execution.execute(() -> "unexpected"));

      assertThat(failure)
          .isInstanceOf(StorageException.Unavailable.class)
          .hasMessage("JDBC executor rejected work")
          .hasCauseInstanceOf(RejectedExecutionException.class);
      assertThat(failure.getCause()).hasMessage("delegate rejected");
    } finally {
      execution.close();
    }
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void validatesExecutionConfiguration() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> JdbcExecution.create(0, 0))
        .withMessage("maxConcurrency must be greater than zero");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> JdbcExecution.create(1, -1))
        .withMessage("maxPending must not be negative");
    assertThatNullPointerException()
        .isThrownBy(() -> JdbcExecution.using(null, 1, 0))
        .withMessage("executor");
    assertThatNullPointerException()
        .isThrownBy(() -> new JdbcExecutionConfig(null, 1, 0))
        .withMessage("mode");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new JdbcExecutionConfig(JdbcExecutionMode.PLATFORM_THREAD, 0, 0))
        .withMessage("maxConcurrency must be greater than zero");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new JdbcExecutionConfig(JdbcExecutionMode.PLATFORM_THREAD, 1, -1))
        .withMessage("maxPending must not be negative");
  }

  private static String blockingOperation(
      CountDownLatch runningStarted,
      CountDownLatch releaseRunning,
      AtomicInteger active,
      AtomicInteger peak) {
    int current = active.incrementAndGet();
    peak.accumulateAndGet(current, Math::max);
    runningStarted.countDown();
    await(releaseRunning);
    active.decrementAndGet();
    return "running";
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test was interrupted", failure);
    }
  }

  private static void awaitTermination(Thread thread) {
    try {
      thread.join(TimeUnit.SECONDS.toMillis(5));
      assertThat(thread.isAlive()).isFalse();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test was interrupted", failure);
    }
  }

  private static <T> T completedValue(CompletionStage<T> stage) {
    var future = stage.toCompletableFuture();
    assertThat(future).succeedsWithin(Duration.ofSeconds(5));
    return future.join();
  }

  private static Throwable completedFailure(CompletionStage<?> stage) {
    try {
      stage.toCompletableFuture().join();
      throw new AssertionError("stage completed successfully");
    } catch (CompletionException failure) {
      return failure.getCause();
    }
  }
}
