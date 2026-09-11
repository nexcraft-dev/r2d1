package dev.nexcraft.r2d1.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nexcraft.r2d1.PersistenceException;
import dev.nexcraft.r2d1.spi.StorageException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StageSupportTest {

  @Test
  void unwrapsTheOriginalRuntimeFailureAtTheSynchronousBoundary() {
    StorageException failure = new StorageException.Unavailable("storage unavailable");
    CompletableFuture<String> stage =
        CompletableFuture.failedFuture(new CompletionException(failure));

    assertThatThrownBy(() -> StageSupport.await(stage)).isSameAs(failure);
  }

  @Test
  void restoresInterruptStatusAtTheSynchronousBoundary() throws Exception {
    CompletableFuture<String> pending = new CompletableFuture<>();
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread caller =
        new Thread(
            () -> {
              started.countDown();
              try {
                StageSupport.await(pending);
              } catch (Throwable failure) {
                thrown.set(failure);
                interrupted.set(Thread.currentThread().isInterrupted());
              }
            });

    caller.start();
    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
    caller.interrupt();
    caller.join(1_000L);

    assertThat(caller.isAlive()).isFalse();
    assertThat(thrown.get())
        .isInstanceOf(PersistenceException.class)
        .hasMessage("Interrupted while waiting for persistence")
        .hasCauseInstanceOf(InterruptedException.class);
    assertThat(interrupted).isTrue();
  }
}
