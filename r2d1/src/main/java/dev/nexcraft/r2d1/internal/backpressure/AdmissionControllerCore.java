package dev.nexcraft.r2d1.internal.backpressure;

import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.spi.AdmissionProvider;
import dev.nexcraft.r2d1.spi.AdmissionRejectedException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Owns the bounded pending queue and the lifecycle of admitted operations. */
public final class AdmissionControllerCore implements AutoCloseable {

  private final Object lock = new Object();
  private final int maxPending;
  private final AdmissionProvider.PermitSource permits;
  private final ArrayDeque<Operation> pending = new ArrayDeque<>();
  private boolean closed;
  private boolean draining;

  /**
   * Creates the controller state machine.
   *
   * @param config concurrency and pending limits
   * @param permits provider-backed concurrency permits
   */
  public AdmissionControllerCore(
      BackpressureConfig config, AdmissionProvider.PermitSource permits) {
    Objects.requireNonNull(config, "config");
    this.maxPending = config.maxPending();
    this.permits = Objects.requireNonNull(permits, "permits");
  }

  /** Submits an operation without waiting for capacity. */
  public <T> CompletionStage<T> submit(Supplier<? extends CompletionStage<T>> operation) {
    Objects.requireNonNull(operation, "operation");
    Request<T> request = new Request<>(this, operation);
    AdmissionRejectedException rejection = null;
    boolean startImmediately = false;

    synchronized (lock) {
      if (closed) {
        request.state = State.FINISHED;
        rejection = new AdmissionRejectedException("Admission controller is closed");
      } else if (pending.isEmpty()) {
        try {
          if (permits.tryAcquire()) {
            request.state = State.RUNNING;
            startImmediately = true;
          } else if (pending.size() < maxPending) {
            request.state = State.QUEUED;
            pending.addLast(request);
          } else {
            request.state = State.FINISHED;
            rejection = capacityRejection();
          }
        } catch (Throwable failure) {
          request.state = State.FINISHED;
          rejection =
              new AdmissionRejectedException(
                  "Admission provider failed to acquire a permit", failure);
        }
      } else if (pending.size() < maxPending) {
        request.state = State.QUEUED;
        pending.addLast(request);
      } else {
        request.state = State.FINISHED;
        rejection = capacityRejection();
      }
    }

    if (rejection != null) {
      request.result.completeExceptionally(rejection);
    } else if (startImmediately) {
      start(request);
    }
    return request.result;
  }

  /** Fails queued work and rejects later submissions without disturbing active operations. */
  @Override
  public void close() {
    List<Operation> rejected;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      rejected = new ArrayList<>(pending);
      pending.clear();
      rejected.forEach(operation -> operation.state = State.FINISHED);
    }

    rejected.forEach(
        operation ->
            operation.fail(new AdmissionRejectedException("Admission controller is closed")));
  }

  private boolean cancel(Operation operation, boolean mayInterruptIfRunning) {
    synchronized (lock) {
      if (operation.state == State.QUEUED) {
        pending.remove(operation);
        operation.state = State.FINISHED;
      }
    }
    return operation.cancelResult(mayInterruptIfRunning);
  }

  private void start(Request<?> request) {
    startTyped(request);
  }

  private <T> void startTyped(Request<T> request) {
    try {
      CompletionStage<T> operationStage =
          Objects.requireNonNull(request.operation.get(), "operation returned a null stage");
      operationStage.whenComplete((value, failure) -> finish(request, value, failure));
    } catch (Throwable failure) {
      finish(request, null, failure);
    }
  }

  private <T> void finish(Request<T> request, T value, Throwable operationFailure) {
    synchronized (lock) {
      if (request.state != State.RUNNING) {
        return;
      }
      request.state = State.FINISHED;
    }

    Throwable resultFailure = operationFailure;
    try {
      permits.release();
    } catch (Throwable releaseFailure) {
      if (resultFailure != null && resultFailure != releaseFailure) {
        releaseFailure.addSuppressed(resultFailure);
      }
      resultFailure = releaseFailure;
    }
    drain();

    if (resultFailure == null) {
      request.result.complete(value);
    } else {
      request.result.completeExceptionally(resultFailure);
    }
  }

  private void drain() {
    synchronized (lock) {
      if (draining || closed) {
        return;
      }
      draining = true;
    }

    while (true) {
      Operation next = null;
      List<Operation> failed = null;
      Throwable providerFailure = null;
      synchronized (lock) {
        if (closed || pending.isEmpty()) {
          draining = false;
          return;
        }
        try {
          if (!permits.tryAcquire()) {
            draining = false;
            return;
          }
          next = pending.removeFirst();
          next.state = State.RUNNING;
        } catch (Throwable failure) {
          providerFailure = failure;
          failed = new ArrayList<>(pending);
          pending.clear();
          failed.forEach(operation -> operation.state = State.FINISHED);
          draining = false;
        }
      }

      if (providerFailure != null) {
        AdmissionRejectedException rejection =
            new AdmissionRejectedException(
                "Admission provider failed to acquire a permit", providerFailure);
        failed.forEach(operation -> operation.fail(rejection));
        return;
      }
      next.start(this);
    }
  }

  private static AdmissionRejectedException capacityRejection() {
    return new AdmissionRejectedException("Admission capacity and pending queue are full");
  }

  private enum State {
    NEW,
    QUEUED,
    RUNNING,
    FINISHED
  }

  private abstract static class Operation {

    State state = State.NEW;

    abstract void start(AdmissionControllerCore controller);

    abstract void fail(Throwable failure);

    abstract boolean cancelResult(boolean mayInterruptIfRunning);
  }

  private static final class Request<T> extends Operation {

    private final Supplier<? extends CompletionStage<T>> operation;
    private final RequestFuture<T> result;

    private Request(
        AdmissionControllerCore controller, Supplier<? extends CompletionStage<T>> operation) {
      this.operation = operation;
      this.result = new RequestFuture<>(controller, this);
    }

    @Override
    void start(AdmissionControllerCore controller) {
      controller.startTyped(this);
    }

    @Override
    void fail(Throwable failure) {
      result.completeExceptionally(failure);
    }

    @Override
    boolean cancelResult(boolean mayInterruptIfRunning) {
      return result.cancelDirectly(mayInterruptIfRunning);
    }
  }

  private static final class RequestFuture<T> extends CompletableFuture<T> {

    private final AdmissionControllerCore controller;
    private final Operation operation;

    private RequestFuture(AdmissionControllerCore controller, Operation operation) {
      this.controller = controller;
      this.operation = operation;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return controller.cancel(operation, mayInterruptIfRunning);
    }

    private boolean cancelDirectly(boolean mayInterruptIfRunning) {
      return super.cancel(mayInterruptIfRunning);
    }
  }
}
