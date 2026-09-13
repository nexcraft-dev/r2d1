package dev.nexcraft.r2d1.jdbc;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/** Runs the reusable SQLite contract with the explicit virtual-thread execution mode. */
@Tag("java-25")
@EnabledForJreRange(min = JRE.JAVA_25)
class SqliteVirtualIndexStoreContractTest extends SqliteIndexStoreContractTest {

  @Override
  protected JdbcExecution createExecution() {
    return createVirtualExecution();
  }
}
