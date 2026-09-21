package dev.nexcraft.r2d1.spi;

/**
 * Supplies a non-blocking permit source for an {@code AdmissionController} concurrency limit.
 *
 * <p>Providers must return immediately from permit operations. The controller owns pending-work
 * bounds and lifecycle; a permit source only reports whether a concurrency permit is available and
 * releases permits after operation stages terminate.
 */
public interface AdmissionProvider {

  /**
   * Creates a permit source for the requested active-operation limit.
   *
   * @param maxConcurrency maximum number of simultaneous permits
   * @return the permit source for this controller
   */
  PermitSource create(int maxConcurrency);

  /** Non-blocking operations used by the admission controller to acquire and release permits. */
  interface PermitSource {

    /**
     * Attempts to acquire one permit without waiting.
     *
     * @return whether a permit was acquired
     */
    boolean tryAcquire();

    /** Releases one permit previously acquired by {@link #tryAcquire()}. */
    void release();
  }
}
