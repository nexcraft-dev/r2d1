package dev.nexcraft.r2d1;

import dev.nexcraft.r2d1.internal.backpressure.AdmissionControllerCore;
import dev.nexcraft.r2d1.spi.AdmissionProvider;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Applies a non-blocking concurrency and pending-work limit to asynchronous downstream I/O.
 *
 * <p>Each controller owns an independent budget. Rejected submissions complete exceptionally with
 * {@link dev.nexcraft.r2d1.spi.AdmissionRejectedException}. Cancelling a running result does not
 * cancel its operation or release its permit; the operation's own stage must reach a terminal state
 * first.
 */
public final class AdmissionController implements AutoCloseable {

  private final AdmissionControllerCore core;

  /**
   * Creates a controller using the installed default admission provider.
   *
   * @param config active and pending operation limits
   * @throws IllegalStateException if no {@link AdmissionProvider} is installed
   */
  public AdmissionController(BackpressureConfig config) {
    this(config, loadDefaultProvider());
  }

  /**
   * Creates a controller using the supplied admission provider.
   *
   * @param config active and pending operation limits
   * @param provider provider that supplies non-blocking concurrency permits
   */
  public AdmissionController(BackpressureConfig config, AdmissionProvider provider) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(provider, "provider");
    this.core = new AdmissionControllerCore(config, provider.create(config.maxConcurrency()));
  }

  /**
   * Submits one operation for admission.
   *
   * <p>The supplier runs on the submitting thread when capacity is available or on the thread
   * completing the operation that releases a permit. This method never waits or dispatches work to
   * another executor.
   *
   * @param operation supplier for the asynchronous operation
   * @param <T> operation result type
   * @return a stage that completes with the operation result or an admission or operation failure
   */
  public <T> CompletionStage<T> submit(Supplier<? extends CompletionStage<T>> operation) {
    return core.submit(operation);
  }

  /**
   * Stops new submissions and fails queued work. Already running operations retain their permits
   * until their operation stages complete.
   */
  @Override
  public void close() {
    core.close();
  }

  private static AdmissionProvider loadDefaultProvider() {
    return ServiceLoader.load(AdmissionProvider.class)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No AdmissionProvider is installed"));
  }
}
