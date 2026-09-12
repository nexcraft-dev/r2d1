package dev.nexcraft.r2d1.jdbc;

import java.nio.file.Path;

class H2IndexStoreContractTest extends AbstractJdbcIndexStoreContractTest {

  @Override
  protected String fileUrl(Path databasePath) {
    return "jdbc:h2:file:" + databasePath.toAbsolutePath();
  }
}
