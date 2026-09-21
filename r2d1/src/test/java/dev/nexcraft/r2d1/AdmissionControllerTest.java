package dev.nexcraft.r2d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.spi.AdmissionProvider;
import dev.nexcraft.r2d1.spi.AdmissionRejectedException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdmissionControllerTest {

  @Test
  void boundsActiveAndPendingWorkAndStartsQueuedWorkInOrder() {
    CountingProvider provider = new CountingProvider();
    AdmissionController controller =
        new AdmissionController(new BackpressureConfig(1, 1), provider);
    CompletableFuture<String> firstOperation = new CompletableFuture<>();
    CompletableFuture<String> secondOperation = new CompletableFuture<>();
    AtomicInteger started = new AtomicInteger();

    CompletionStage<String> first =
        controller.submit(
            () -> {
              started.incrementAndGet();
              return firstOperation;
            });
    CompletionStage<String> second =
        controller.submit(
            () -> {
              started.incrementAndGet();
              return secondOperation;
            });
    CompletionStage<String> rejected =
        controller.submit(() -> CompletableFuture.completedFuture("third"));

    assertThat(started).hasValue(1);
    assertRejected(rejected);
    assertThat(provider.active).hasValue(1);

    firstOperation.complete("first");

    assertThat(first).isCompletedWithValue("first");
    assertThat(started).hasValue(2);
    assertThat(provider.active).hasValue(1);

    secondOperation.complete("second");

    assertThat(second).isCompletedWithValue("second");
    assertThat(provider.active).hasValue(0);
    assertThat(provider.acquisitions).hasValue(2);
    assertThat(provider.releases).hasValue(2);
  }

  @Test
  void releasesPermitAfterOperationFailureAndSupplierThrow() {
    CountingProvider provider = new CountingProvider();
    AdmissionController controller =
        new AdmissionController(new BackpressureConfig(1, 0), provider);
    IllegalStateException operationFailure = new IllegalStateException("operation failed");
    CompletionStage<String> failedOperation =
        controller.submit(() -> CompletableFuture.failedFuture(operationFailure));

    assertThatThrownBy(failedOperation.toCompletableFuture()::join)
        .hasCauseReference(operationFailure);

    IllegalArgumentException supplierFailure = new IllegalArgumentException("supplier failed");
    CompletionStage<String> failedSupplier =
        controller.submit(
            () -> {
              throw supplierFailure;
            });

    assertThatThrownBy(failedSupplier.toCompletableFuture()::join)
        .hasCauseReference(supplierFailure);
    assertThat(provider.active).hasValue(0);
    assertThat(provider.acquisitions).hasValue(2);
    assertThat(provider.releases).hasValue(2);
  }

  @Test
  void cancellationRemovesQueuedWorkAndDoesNotReleaseRunningPermitEarly() {
    CountingProvider provider = new CountingProvider();
    AdmissionController controller =
        new AdmissionController(new BackpressureConfig(1, 2), provider);
    CompletableFuture<String> runningOperation = new CompletableFuture<>();
    CompletableFuture<String> admittedAfterCancellation = new CompletableFuture<>();
    AtomicInteger queuedSupplierCalls = new AtomicInteger();
    CompletionStage<String> running = controller.submit(() -> runningOperation);
    CompletableFuture<String> cancelled =
        controller
            .submit(
                () -> {
                  queuedSupplierCalls.incrementAndGet();
                  return CompletableFuture.completedFuture("cancelled");
                })
            .toCompletableFuture();

    assertThat(cancelled.cancel(true)).isTrue();
    CompletionStage<String> admitted = controller.submit(() -> admittedAfterCancellation);

    assertThat(queuedSupplierCalls).hasValue(0);
    assertThat(provider.active).hasValue(1);
    running.toCompletableFuture().cancel(true);
    assertThat(provider.active).hasValue(1);

    runningOperation.complete("finished after cancellation");

    assertThat(running).isCancelled();
    assertThat(admittedAfterCancellation).isNotCompleted();
    assertThat(provider.active).hasValue(1);
    admittedAfterCancellation.complete("admitted");
    assertThat(admitted).isCompletedWithValue("admitted");
    assertThat(provider.active).hasValue(0);
    assertThat(provider.releases).hasValue(2);
    assertThat(queuedSupplierCalls).hasValue(0);
  }

  @Test
  void closeFailsQueuedAndFutureSubmissionsButLetsActiveWorkReleaseNormally() {
    CountingProvider provider = new CountingProvider();
    AdmissionController controller =
        new AdmissionController(new BackpressureConfig(1, 1), provider);
    CompletableFuture<String> activeOperation = new CompletableFuture<>();
    CompletionStage<String> active = controller.submit(() -> activeOperation);
    CompletionStage<String> queued =
        controller.submit(() -> CompletableFuture.completedFuture("queued"));

    controller.close();

    assertRejected(queued);
    assertRejected(controller.submit(() -> CompletableFuture.completedFuture("after close")));
    assertThat(provider.active).hasValue(1);
    activeOperation.complete("done");
    assertThat(active).isCompletedWithValue("done");
    assertThat(provider.active).hasValue(0);
    assertThat(provider.releases).hasValue(1);
  }

  @Test
  void releasesACompletedOperationOnlyOnceWhenCompletionRaces() throws Exception {
    CountingProvider provider = new CountingProvider();
    AdmissionController controller =
        new AdmissionController(new BackpressureConfig(1, 0), provider);
    CompletableFuture<String> operation = new CompletableFuture<>();
    controller.submit(() -> operation);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Thread completeNormally =
        new Thread(
            () -> {
              ready.countDown();
              await(start);
              operation.complete("complete");
            });
    Thread completeExceptionally =
        new Thread(
            () -> {
              ready.countDown();
              await(start);
              operation.completeExceptionally(new IllegalStateException("race"));
            });

    completeNormally.start();
    completeExceptionally.start();
    assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    completeNormally.join(1_000L);
    completeExceptionally.join(1_000L);

    assertThat(completeNormally.isAlive()).isFalse();
    assertThat(completeExceptionally.isAlive()).isFalse();
    assertThat(provider.active).hasValue(0);
    assertThat(provider.acquisitions).hasValue(1);
    assertThat(provider.releases).hasValue(1);
  }

  @Test
  void defaultProviderIsLoadedThroughTheServiceBoundary() {
    AdmissionController controller = new AdmissionController(new BackpressureConfig(1, 0));
    CompletableFuture<String> operation = new CompletableFuture<>();
    CompletionStage<String> admitted = controller.submit(() -> operation);
    CompletionStage<String> rejected =
        controller.submit(() -> CompletableFuture.completedFuture("overflow"));

    assertRejected(rejected);
    operation.complete("complete");
    assertThat(admitted).isCompletedWithValue("complete");
  }

  private static void assertRejected(CompletionStage<?> stage) {
    assertThat(stage.toCompletableFuture()).isCompletedExceptionally();
    assertThatThrownBy(() -> stage.toCompletableFuture().join())
        .hasCauseInstanceOf(AdmissionRejectedException.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private static final class CountingProvider implements AdmissionProvider {

    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger acquisitions = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();

    @Override
    public PermitSource create(int maxConcurrency) {
      return new PermitSource() {
        @Override
        public boolean tryAcquire() {
          while (true) {
            int current = active.get();
            if (current >= maxConcurrency) {
              return false;
            }
            if (active.compareAndSet(current, current + 1)) {
              acquisitions.incrementAndGet();
              return true;
            }
          }
        }

        @Override
        public void release() {
          int remaining = active.decrementAndGet();
          if (remaining < 0) {
            throw new AssertionError("released a permit that was not acquired");
          }
          releases.incrementAndGet();
        }
      };
    }
  }
}
