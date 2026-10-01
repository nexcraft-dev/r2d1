package dev.nexcraft.r2d1.quarkus.it;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application owns the blocking filesystem executor; Quarkus owns its H2 datasource. */
@Singleton
public final class ApplicationResources {
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  /** Named resource avoids ambiguity with Quarkus worker executors. */
  @Produces
  @Singleton
  @Named("documents")
  Executor documents() {
    return executor;
  }

  /** Application, rather than the R2D1 extension, shuts down this executor. */
  @PreDestroy
  void close() {
    executor.shutdown();
  }
}
