package dev.nexcraft.r2d1.d1.internal.transport;

import dev.nexcraft.r2d1.AdmissionController;
import dev.nexcraft.r2d1.d1.internal.sql.D1Result;
import dev.nexcraft.r2d1.d1.internal.sql.D1Statement;
import dev.nexcraft.r2d1.spi.AdmissionRejectedException;
import dev.nexcraft.r2d1.spi.StorageException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Applies a D1 admission permit to each execution at the common transport boundary. */
public final class AdmittedD1Transport implements D1Transport {

  private final D1Transport delegate;
  private final AdmissionController admission;

  /**
   * Wraps a D1 transport so every executed statement has its own admission permit.
   *
   * @param delegate underlying D1 transport
   * @param admission D1-specific admission controller
   */
  public AdmittedD1Transport(D1Transport delegate, AdmissionController admission) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.admission = Objects.requireNonNull(admission, "admission");
  }

  @Override
  public CompletionStage<D1Result> execute(D1Statement statement) {
    Objects.requireNonNull(statement, "statement");
    return admission.submit(
        () -> {
          try {
            return Objects.requireNonNull(
                delegate.execute(statement), "D1 transport returned a null stage");
          } catch (RuntimeException failure) {
            if (failure instanceof StorageException
                || failure instanceof AdmissionRejectedException) {
              return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.failedFuture(
                new StorageException.Operation("D1 operation failed", failure));
          }
        });
  }

  @Override
  public void close() {
    delegate.close();
  }
}
