package dev.nexcraft.r2d1.d1.internal.transport;

import dev.nexcraft.r2d1.d1.D1Config;
import dev.nexcraft.r2d1.d1.internal.sql.D1Result;
import dev.nexcraft.r2d1.d1.internal.sql.D1Statement;
import java.util.concurrent.CompletionStage;

/** Internal transport boundary that isolates D1 SQL execution from the REST implementation. */
public interface D1Transport extends AutoCloseable {

  /** Creates the production Cloudflare REST transport for one D1 configuration. */
  static D1Transport rest(D1Config config) {
    return new RestD1Transport(config);
  }

  CompletionStage<D1Result> execute(D1Statement statement);

  @Override
  default void close() {}
}
