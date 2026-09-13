package dev.nexcraft.r2d1.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/** Verifies remote HSQLDB deployment behavior with Java 25 virtual-thread execution. */
@Tag("java-25")
@EnabledForJreRange(min = JRE.JAVA_25)
class HsqldbRemoteVirtualDeploymentTest extends HsqldbRemoteDeploymentTest {

  @Override
  protected JdbcExecution createExecution() {
    return createVirtualExecution();
  }
}
