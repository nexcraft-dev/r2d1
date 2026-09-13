package dev.nexcraft.r2d1.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/** Runs the remote H2 contract with Java 25 virtual-thread execution. */
@Tag("java-25")
@EnabledForJreRange(min = JRE.JAVA_25)
class H2RemoteVirtualIndexStoreContractTest extends H2RemoteIndexStoreContractTest {

  @Override
  protected JdbcExecution createExecution() {
    return createVirtualExecution();
  }
}
