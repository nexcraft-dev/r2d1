package dev.nexcraft.r2d1.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/** Runs the reusable H2 contract with the explicit virtual-thread execution mode. */
@Tag("java-25")
@EnabledForJreRange(min = JRE.JAVA_25)
class H2VirtualIndexStoreContractTest extends H2IndexStoreContractTest {

  @Override
  protected JdbcExecution createExecution() {
    return createVirtualExecution();
  }
}
