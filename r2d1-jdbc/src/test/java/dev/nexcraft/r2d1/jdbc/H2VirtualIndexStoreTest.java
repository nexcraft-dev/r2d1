package dev.nexcraft.r2d1.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/** Runs the complete H2 JDBC lifecycle suite with explicit virtual-thread execution. */
@Tag("java-25")
@EnabledForJreRange(min = JRE.JAVA_25)
class H2VirtualIndexStoreTest extends H2IndexStoreTest {

  @Override
  protected JdbcExecution createExecution(int maxConcurrency, int maxPending) {
    return createVirtualExecution(maxConcurrency, maxPending);
  }
}
