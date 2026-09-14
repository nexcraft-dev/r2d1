package dev.nexcraft.r2d1.micronaut;

/** IndexStore backend that Micronaut should create when no application bean is present. */
public enum IndexBackend {
  /** Use the optional R2D1 JDBC adapter. */
  JDBC,

  /** Use the built-in Cloudflare D1 adapter. */
  D1
}
