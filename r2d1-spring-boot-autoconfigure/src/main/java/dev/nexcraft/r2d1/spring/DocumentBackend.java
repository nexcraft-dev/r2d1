package dev.nexcraft.r2d1.spring;

/** DocumentStore backend that Spring Boot should create when no application bean is present. */
public enum DocumentBackend {
  /** Use Cloudflare R2 through the core R2D1 adapter. */
  R2,

  /** Use the optional filesystem adapter. */
  FILESYSTEM
}
