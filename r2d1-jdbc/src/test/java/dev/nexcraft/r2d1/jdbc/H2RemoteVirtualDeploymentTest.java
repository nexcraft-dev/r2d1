package dev.nexcraft.r2d1.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/** Verifies remote H2 deployment behavior with Java 25 virtual-thread execution. */
@Tag("java-25")
@EnabledForJreRange(min = JRE.JAVA_25)
class H2RemoteVirtualDeploymentTest extends H2RemoteDeploymentTest {

  @Override
  protected JdbcExecution createExecution() {
    return createVirtualExecution();
  }
}
