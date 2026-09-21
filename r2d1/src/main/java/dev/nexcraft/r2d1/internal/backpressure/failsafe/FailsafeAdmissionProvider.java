package dev.nexcraft.r2d1.internal.backpressure.failsafe;

import dev.failsafe.Bulkhead;
import dev.nexcraft.r2d1.spi.AdmissionProvider;

/** Default admission provider backed by Failsafe's immediate bulkhead permits. */
public final class FailsafeAdmissionProvider implements AdmissionProvider {

  /** Creates the service-loaded provider. */
  public FailsafeAdmissionProvider() {}

  @Override
  public PermitSource create(int maxConcurrency) {
    if (maxConcurrency <= 0) {
      throw new IllegalArgumentException("maxConcurrency must be greater than zero");
    }
    Bulkhead bulkhead = Bulkhead.of(maxConcurrency);
    return new PermitSource() {
      @Override
      public boolean tryAcquire() {
        return bulkhead.tryAcquirePermit();
      }

      @Override
      public void release() {
        bulkhead.releasePermit();
      }
    };
  }
}
