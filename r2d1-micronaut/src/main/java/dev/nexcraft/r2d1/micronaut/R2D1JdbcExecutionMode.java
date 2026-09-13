package dev.nexcraft.r2d1.micronaut;

/** Thread model for an R2D1-managed JDBC execution resource. */
public enum R2D1JdbcExecutionMode {
  /** Execute blocking JDBC work on bounded platform threads. */
  PLATFORM_THREAD,

  /** Execute admitted blocking JDBC work on virtual threads. */
  VIRTUAL_THREAD
}
