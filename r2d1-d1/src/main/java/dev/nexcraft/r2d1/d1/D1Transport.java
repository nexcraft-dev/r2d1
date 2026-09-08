package dev.nexcraft.r2d1.d1;

import java.util.concurrent.CompletionStage;

/** Internal transport boundary that isolates D1 SQL execution from the REST implementation. */
interface D1Transport extends AutoCloseable {

  CompletionStage<D1Result> execute(D1Statement statement);

  @Override
  default void close() {}
}
