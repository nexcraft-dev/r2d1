/**
 * JDBC infrastructure and built-in asynchronous R2D1 index storage for embedded or remote H2 and
 * HSQLDB databases and local embedded SQLite files.
 *
 * <p>Applications supply and own the {@code DataSource}, endpoint configuration, credentials, TLS,
 * pooling, server lifecycle, and {@link dev.nexcraft.r2d1.jdbc.JdbcExecution}. Execution mode is
 * independent of database deployment topology. JDBC storage contains only a rebuildable index
 * projection; authoritative documents remain in a {@link dev.nexcraft.r2d1.spi.DocumentStore}.
 */
@NullMarked
package dev.nexcraft.r2d1.jdbc;

import org.jspecify.annotations.NullMarked;
