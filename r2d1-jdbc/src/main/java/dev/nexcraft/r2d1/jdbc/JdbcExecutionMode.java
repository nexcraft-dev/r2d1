package dev.nexcraft.r2d1.jdbc;

/** Selects the thread model used by an R2D1-managed JDBC execution resource. */
public enum JdbcExecutionMode {
  /** Executes blocking JDBC operations on bounded platform threads. */
  PLATFORM_THREAD,

  /** Executes admitted blocking JDBC operations on virtual threads. */
  VIRTUAL_THREAD
}
