package dev.nexcraft.r2d1.spring;

/** IndexStore backend that Spring Boot should create when no application bean is present. */
public enum IndexBackend {
  /** Use Cloudflare D1 through the core R2D1 adapter. */
  D1,

  /** Use the optional JDBC adapter over an application-owned DataSource. */
  JDBC
}
