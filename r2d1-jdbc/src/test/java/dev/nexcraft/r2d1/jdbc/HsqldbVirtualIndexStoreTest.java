package dev.nexcraft.r2d1.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/** Runs the complete HSQLDB JDBC lifecycle suite with explicit virtual-thread execution. */
@Tag("java-25")
@EnabledForJreRange(min = JRE.JAVA_25)
class HsqldbVirtualIndexStoreTest extends HsqldbIndexStoreTest {

  @Override
  protected JdbcExecution createExecution(int maxConcurrency, int maxPending) {
    return createVirtualExecution(maxConcurrency, maxPending);
  }
}
