package dev.nexcraft.r2d1.jdbc;

import dev.nexcraft.r2d1.spi.StorageException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Bounded execution resource that adapts blocking JDBC work to {@link CompletionStage}.
 *
 * <p>The default factory-created form owns a fixed set of platform threads. An explicit {@link
 * JdbcExecutionConfig} can select the supported virtual-thread mode. The caller-provided form never
 * owns or closes its executor; the caller chooses whether that executor uses platform or virtual
 * threads, and it must dispatch work away from asynchronous event-loop and SDK completion threads.
 * All forms enforce the configured running and pending-work limits before submitting work to the
 * underlying executor.
 *
 * <p>The selected thread model is independent of JDBC deployment topology. It does not determine
 * whether a caller-owned {@code DataSource} uses an embedded database, a remote server, or a
 * connection pool, and it does not change the number of physical connections available from that
 * source.
 *
 * <p>Closing this resource rejects new work, fails work that is still pending, and allows work that
 * has already been submitted to finish. Only an executor created by an R2D1-managed {@code create}
 * method is shut down.
 */
public final class JdbcExecution implements AutoCloseable {

  private static final AtomicLong EXECUTION_IDS = new AtomicLong();
  private static final int MIN_VIRTUAL_THREAD_RUNTIME = 25;

  private final Object lifecycleLock = new Object();
  private final Executor executor;
  private final @Nullable ExecutorService ownedExecutor;
  private final int maxConcurrency;
  private final int maxPending;
  private final ArrayDeque<Task<?>> pending = new ArrayDeque<>();
  private int active;
  private boolean closed;

  private JdbcExecution(
      Executor executor,
      @Nullable ExecutorService ownedExecutor,
      int maxConcurrency,
      int maxPending) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.ownedExecutor = ownedExecutor;
    this.maxConcurrency = requireMaxConcurrency(maxConcurrency);
    this.maxPending = requireMaxPending(maxPending);
  }

  /**
   * Creates a bounded execution resource backed by owned platform threads.
   *
   * @param maxConcurrency positive maximum number of concurrently running JDBC operations
   * @param maxPending non-negative maximum number of JDBC operations waiting to run
   * @return an execution resource that must be closed by its creator
   * @throws IllegalArgumentException if either limit is invalid
   */
  public static JdbcExecution create(int maxConcurrency, int maxPending) {
    return createPlatform(maxConcurrency, maxPending);
  }

  /**
   * Creates a bounded execution resource using the explicitly selected execution mode.
   *
   * <p>{@link JdbcExecutionMode#VIRTUAL_THREAD} requires Java 25 or newer and never falls back to
   * platform threads. The returned resource owns its executor and must be closed by its creator.
   *
   * @param config execution mode and admission limits
   * @return an R2D1-managed execution resource
   * @throws NullPointerException if {@code config} is {@code null}
   * @throws UnsupportedOperationException if virtual-thread mode is requested on Java 24 or older
   */
  public static JdbcExecution create(JdbcExecutionConfig config) {
    Objects.requireNonNull(config, "config");
    return switch (config.mode()) {
      case PLATFORM_THREAD -> createPlatform(config.maxConcurrency(), config.maxPending());
      case VIRTUAL_THREAD -> createVirtual(config.maxConcurrency(), config.maxPending());
    };
  }

  private static JdbcExecution createPlatform(int maxConcurrency, int maxPending) {
    requireMaxConcurrency(maxConcurrency);
    requireMaxPending(maxPending);
    long executionId = EXECUTION_IDS.incrementAndGet();
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            maxConcurrency,
            maxConcurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(maxConcurrency),
            Thread.ofPlatform().name("r2d1-jdbc-" + executionId + "-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    return new JdbcExecution(executor, executor, maxConcurrency, maxPending);
  }

  private static JdbcExecution createVirtual(int maxConcurrency, int maxPending) {
    requireVirtualThreadRuntime();
    requireMaxConcurrency(maxConcurrency);
    requireMaxPending(maxPending);
    long executionId = EXECUTION_IDS.incrementAndGet();
    ExecutorService executor =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("r2d1-jdbc-virtual-" + executionId + "-", 0).factory());
    return new JdbcExecution(executor, executor, maxConcurrency, maxPending);
  }

  /**
   * Creates a bounded execution resource over a caller-owned executor.
   *
   * <p>The supplied executor must run blocking JDBC work on suitable dedicated threads. It may be
   * platform- or virtual-thread-backed; R2D1 does not select, inspect, or shut down that thread
   * model. Closing the returned resource never shuts down the supplied executor.
   *
   * @param executor caller-owned executor for blocking JDBC work
   * @param maxConcurrency positive maximum number of concurrently running JDBC operations
   * @param maxPending non-negative maximum number of JDBC operations waiting to run
   * @return an execution resource that limits admission without owning {@code executor}
   * @throws NullPointerException if {@code executor} is {@code null}
   * @throws IllegalArgumentException if either limit is invalid
   */
  public static JdbcExecution using(Executor executor, int maxConcurrency, int maxPending) {
    return new JdbcExecution(
        Objects.requireNonNull(executor, "executor"), null, maxConcurrency, maxPending);
  }

  <T extends @Nullable Object> CompletionStage<T> execute(Callable<T> operation) {
    Objects.requireNonNull(operation, "operation");
    Task<T> task = new Task<>(operation, new CompletableFuture<>());
    boolean dispatch;
    synchronized (lifecycleLock) {
      if (closed) {
        task.reject("JDBC execution is closed");
        return task.result();
      }
      if (active < maxConcurrency) {
        active++;
        dispatch = true;
      } else if (pending.size() < maxPending) {
        pending.addLast(task);
        dispatch = false;
      } else {
        task.reject("JDBC execution capacity is exhausted");
        return task.result();
      }
    }
    if (dispatch) {
      dispatch(task);
    }
    return task.result();
  }

  /**
   * Stops accepting work and releases resources owned by this execution strategy.
   *
   * <p>This method is idempotent and never waits for submitted JDBC work to finish.
   */
  @Override
  public void close() {
    List<Task<?>> rejected;
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
      rejected = new ArrayList<>(pending);
      pending.clear();
    }
    rejected.forEach(task -> task.reject("JDBC execution is closed"));
    if (ownedExecutor != null) {
      ownedExecutor.shutdown();
    }
  }

  private void dispatch(Task<?> first) {
    Task<?> current = first;
    while (current != null) {
      Task<?> submitted = current;
      try {
        executor.execute(() -> run(submitted));
        return;
      } catch (RuntimeException failure) {
        submitted.fail(executorFailure(failure));
        current = releaseAndTakeNext();
      }
    }
  }

  private <T extends @Nullable Object> void run(Task<T> task) {
    try {
      task.result().complete(task.operation().call());
    } catch (Throwable failure) {
      task.result().completeExceptionally(failure);
    } finally {
      Task<?> next = releaseAndTakeNext();
      if (next != null) {
        dispatch(next);
      }
    }
  }

  private @Nullable Task<?> releaseAndTakeNext() {
    synchronized (lifecycleLock) {
      active--;
      if (closed || pending.isEmpty()) {
        return null;
      }
      active++;
      return pending.removeFirst();
    }
  }

  private static StorageException executorFailure(RuntimeException failure) {
    if (failure instanceof RejectedExecutionException) {
      return new StorageException.Unavailable("JDBC executor rejected work", failure);
    }
    return new StorageException.Operation("JDBC executor could not accept work", failure);
  }

  private static void requireVirtualThreadRuntime() {
    int runtimeFeature = Runtime.version().feature();
    if (runtimeFeature < MIN_VIRTUAL_THREAD_RUNTIME) {
      throw new UnsupportedOperationException(
          "VIRTUAL_THREAD requires Java "
              + MIN_VIRTUAL_THREAD_RUNTIME
              + " or newer; current runtime is Java "
              + runtimeFeature);
    }
  }

  private static int requireMaxConcurrency(int value) {
    if (value <= 0) {
      throw new IllegalArgumentException("maxConcurrency must be greater than zero");
    }
    return value;
  }

  private static int requireMaxPending(int value) {
    if (value < 0) {
      throw new IllegalArgumentException("maxPending must not be negative");
    }
    return value;
  }

  private record Task<T extends @Nullable Object>(
      Callable<T> operation, CompletableFuture<T> result) {

    private void reject(String message) {
      result.completeExceptionally(
          new StorageException.Unavailable(message, new RejectedExecutionException(message)));
    }

    private void fail(RuntimeException failure) {
      result.completeExceptionally(failure);
    }
  }
}
