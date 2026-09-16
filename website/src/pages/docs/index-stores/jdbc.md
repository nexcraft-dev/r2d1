---
layout: ../../../layouts/DocsLayout.astro
title: JDBC IndexStore
description: Configure the JDBC index store for H2, HSQLDB, or local SQLite.
---

`JdbcIndexStore` adapts blocking JDBC databases to R2D1's asynchronous `IndexStore` SPI. It stores
document identifiers and indexed field projections only. The authoritative serialized document
remains in the configured `DocumentStore`.

## Supported databases

| Database | Supported topology |
| --- | --- |
| H2 | Persistent embedded file and TCP server databases |
| HSQLDB | Persistent embedded file and HSQL server databases |
| SQLite | Local persistent embedded file database only |

The module selects a built-in dialect from the exact JDBC product name. Other database products fail
before schema mutation. The three JDBC drivers are not bundled or exposed transitively; the
application supplies the driver selected by its `DataSource`.

## Construct the store

```kotlin
implementation("dev.nexcraft:r2d1-jdbc:{{latestStableVersion}}")
```

Create a bounded execution resource for blocking JDBC work and pass the application-owned
`DataSource` to the store:

```java
import dev.nexcraft.r2d1.jdbc.JdbcExecution;
import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
import javax.sql.DataSource;

DataSource dataSource = applicationDataSource;

try (JdbcExecution execution = JdbcExecution.create(4, 64)) {
  JdbcIndexStore indexes = new JdbcIndexStore(dataSource, execution);
  // Pass indexes and indexes::initialize to PersistenceCollectionFactory.
}
```

`JdbcIndexStore` owns neither the `DataSource` nor the `JdbcExecution`. `JdbcExecution.create(...)`
owns the executor it creates and must be closed by its creator. `JdbcExecution.using(...)` wraps a
caller-owned executor and never shuts it down.

## Execution mode

Platform threads are the default. Virtual threads are an explicit Java 25 or newer option and never
fall back silently:

```java
JdbcExecutionConfig config =
    new JdbcExecutionConfig(JdbcExecutionMode.VIRTUAL_THREAD, 100, 500);
JdbcExecution execution = JdbcExecution.create(config);
```

`maxConcurrency` limits running operations and `maxPending` limits admitted work waiting to run.
These limits do not select a database topology, change the number of physical connections, or
replace a connection pool.

The default framework admission budget is 8 active and 32 pending operations. Spring Boot and
Micronaut inherit the global `r2d1.backpressure.*` values and accept JDBC-specific
`r2d1.jdbc.backpressure.*` overrides. Each field inherits independently. The legacy flat keys
`r2d1.jdbc.max-concurrency` and `r2d1.jdbc.max-pending` remain explicit aliases; an unset flat key
does not override the global setting with the old 4/64 framework binding defaults.

When all active and pending slots are occupied, another JDBC operation fails immediately with
`AdmissionRejectedException`. Cancelling the public result does not release a permit while the
blocking JDBC callable is still running. Admission does not increase a DataSource's physical
connection capacity or alter the selected executor's thread mode.

## Schema and values

Each collection maps to one table with a `document_id` primary key and one required column per
`@Index` field. Required single-column indexes are created or validated; existing compatible schema
objects are reused. Initialization does not drop, rename, or convert existing objects.

The supported logical indexed values are `String`, `long`/`Long`, `double`/`Double`, and
`boolean`/`Boolean`. Every indexed value must be present and non-null when a document is written.
`Instant` is not supported as an indexed value by the built-in dialects.

## SQLite boundary

SQLite is supported as a local persistent file database. It is not a remote SQLite service. R2D1
applies `PRAGMA busy_timeout=5000` to operation connections and coordinates writes within one
`JdbcIndexStore` instance. Separate store instances and external processes still coordinate through
SQLite's locking behavior.

## Query and lifecycle behavior

JDBC queries use prepared statements, AND-combine multiple filters, and use keyset cursors. An
explicit sort is followed by `document_id` in the same direction; a query without an explicit sort
uses `document_id ASC`. See [Querying](/docs/querying/) for the public builder and
[Configuration](/docs/configuration/) for ownership boundaries.
