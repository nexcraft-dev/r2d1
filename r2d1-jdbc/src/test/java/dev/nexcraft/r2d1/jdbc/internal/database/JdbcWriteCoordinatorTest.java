package dev.nexcraft.r2d1.jdbc.internal.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JdbcWriteCoordinatorTest {

  @Test
  void serialCoordinatorAllowsOnlyOneActiveWrite() throws Exception {
    JdbcWriteCoordinator coordinator = JdbcWriteCoordinator.serial();
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<?> first =
          executor.submit(
              () -> {
                coordinator.execute(
                    () -> {
                      int current = active.incrementAndGet();
                      peak.accumulateAndGet(current, Math::max);
                      firstEntered.countDown();
                      await(releaseFirst);
                      active.decrementAndGet();
                    });
                return null;
              });
      assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

      Future<?> second =
          executor.submit(
              () -> {
                secondStarted.countDown();
                coordinator.execute(
                    () -> {
                      int current = active.incrementAndGet();
                      peak.accumulateAndGet(current, Math::max);
                      secondEntered.countDown();
                      active.decrementAndGet();
                    });
                return null;
              });
      assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(secondEntered.await(Duration.ofMillis(100).toMillis(), TimeUnit.MILLISECONDS))
          .isFalse();

      releaseFirst.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    }

    assertThat(peak).hasValue(1);
  }

  private static void await(CountDownLatch latch) throws SQLException {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new SQLException("test write was interrupted", failure);
    }
  }
}
