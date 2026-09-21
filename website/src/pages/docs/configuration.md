---
layout: ../../layouts/DocsLayout.astro
title: Configuration
description: Public artifacts, storage combinations, resource ownership, and runtime boundaries.
---

## Public modules

The latest stable release exposes four artifacts. The Spring Boot integration is an upcoming pair of
artifacts targeted for the next release and is not yet available from Maven Central:

| Module | Coordinate | Provides |
| --- | --- | --- |
| `R2D1` | `dev.nexcraft:r2d1` | Common API, Cloudflare R2 `DocumentStore`, and Cloudflare D1 `IndexStore` |
| `R2D1-FILESYSTEM` | `dev.nexcraft:r2d1-filesystem` | Filesystem `DocumentStore` |
| `R2D1-JDBC` | `dev.nexcraft:r2d1-jdbc` | H2, HSQLDB, and SQLite `IndexStore` support |
| `R2D1-MICRONAUT` | `dev.nexcraft:r2d1-micronaut` | Micronaut 5 configuration and bean integration |
| `R2D1-SPRING-BOOT-AUTOCONFIGURE` | `dev.nexcraft:r2d1-spring-boot-autoconfigure` | Upcoming Spring Boot 4 auto-configuration |
| `R2D1-SPRING-BOOT-STARTER` | `dev.nexcraft:r2d1-spring-boot-starter` | Upcoming Spring Boot 4 convenience starter |

The following stable dependencies are rendered with the website's current stable release version:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1:{{latestStableVersion}}")
    implementation("dev.nexcraft:r2d1-filesystem:{{latestStableVersion}}")
    implementation("dev.nexcraft:r2d1-jdbc:{{latestStableVersion}}")
    implementation("dev.nexcraft:r2d1-micronaut:{{latestStableVersion}}")
}
```

The base artifact is transitive from the filesystem, JDBC, and Micronaut integrations where the
repository declares that relationship. Do not add every integration by default.

## Document store backends

Choose one authoritative document backend:

| Backend | Vendor or runtime | Setup |
| --- | --- | --- |
| R2 | Cloudflare R2 | [R2 DocumentStore](/docs/document-stores/r2/) |
| Filesystem | Local filesystem | [Filesystem DocumentStore](/docs/document-stores/filesystem/) |

The document store owns complete serialized content and logical existence. It does not perform
indexed filtering.

## Index store backends

Choose one rebuildable index backend:

| Backend | Vendor or runtime | Setup |
| --- | --- | --- |
| D1 | Cloudflare D1 REST API | [D1 IndexStore](/docs/index-stores/d1/) |
| JDBC | H2, HSQLDB, or local SQLite | [JDBC IndexStore](/docs/index-stores/jdbc/) |

The index store owns document identifiers and explicitly indexed fields for filtering, sorting,
and cursor pagination. It is not authoritative for complete documents.

## Compose one backend from each role

The document and index roles compose independently:

| DocumentStore | IndexStore | Intended use |
| --- | --- | --- |
| R2 | D1 | Cloudflare object documents and Cloudflare indexed lookup |
| R2 | JDBC | Cloudflare documents with an application-selected SQL index |
| Filesystem | D1 | Local document files with a D1 projection |
| Filesystem | JDBC | Fully local document and index storage |

These four pairings use the same collection API. The core orchestration does not select a
deployment topology; it receives the two stores through `PersistenceCollectionFactory`.

## Resource ownership

The application owns resources it supplies:

- `DocumentCodec`, `DataSource`, JDBC driver, connection pool, and database server;
- caller-provided executors used by the filesystem or JDBC adapters; and
- any `S3AsyncClient` or `HttpClient` passed to an adapter.

The configuration constructors that create adapter clients own those clients and close them through
their `AutoCloseable` stores. Closing R2D1's stores does not close unrelated application resources.

## Runtime boundaries

The public collection API is synchronous, while the storage SPI returns `CompletionStage`. R2 uses
the AWS SDK asynchronous Netty client, D1 uses Java's asynchronous `HttpClient`, and blocking
filesystem/JDBC operations run on caller-selected execution resources.

There is no global R2D1 thread-mode switch. JDBC execution limits and thread selection are local to
`JdbcExecution`; they do not change a `DataSource`, a connection pool, or a database server's
topology.

## Backpressure and admission

R2D1 applies a non-blocking admission budget to each R2, D1, JDBC, and Filesystem adapter. R2, D1,
and Filesystem default constructors and framework-created adapters use **8 active operations and 32
pending operations**. A directly created JDBC `JdbcExecution` continues to take its limits from
`JdbcExecutionConfig`. These are local R2D1 policy defaults, not Cloudflare service limits. Each
adapter gets an independent budget; global values provide fallback settings and do not create one
shared controller.

Spring Boot and Micronaut accept global and adapter-specific values. Filesystem properties are
available in Spring Boot, and the core Filesystem constructor accepts `BackpressureConfig` directly:

```yaml
r2d1:
  backpressure:
    max-concurrency: 8
    max-pending: 32
  r2:
    backpressure:
      max-concurrency: 12
    client:
      max-concurrency: 16
  d1:
    backpressure:
      max-pending: 8
  jdbc:
    backpressure:
      max-concurrency: 4
      max-pending: 16
  filesystem:
    backpressure:
      max-concurrency: 2
      max-pending: 4
```

Limits inherit independently for each field: adapter setting, then global setting, then the 8/32
library default. JDBC also accepts the legacy flat keys `r2d1.jdbc.max-concurrency` and
`r2d1.jdbc.max-pending`; an explicitly supplied flat value is below the new nested JDBC setting and
above the global value in precedence. Leaving a flat key unset does not apply its former 4/64
framework binding defaults.

Every downstream I/O operation acquires capacity before it starts and retains that capacity until
its operation stage is complete. A caller-supplied executor or client is not resized, closed, or
inspected. When both active and pending capacity are full, the operation fails immediately with
`AdmissionRejectedException`; this local overload failure is distinct from `StorageException`.
Cancelling the returned stage does not release a running operation's capacity before the underlying
I/O finishes.

Query and rebuild fan-out can exceed a backend's combined active and pending limits. Such fan-out
is rejected instead of growing an unbounded queue. Rebuild prepares its first source page before
clearing D1 so an initial admission rejection leaves existing index rows intact; later failures can
still leave a partial rebuild.

These limits control simultaneous operations, not requests per second. They do not implement
Cloudflare API rate limiting or Reactive Streams demand/backpressure.

See the adapter pages for credentials, schema initialization, and lifecycle details:

- [R2 DocumentStore](/docs/document-stores/r2/)
- [Filesystem DocumentStore](/docs/document-stores/filesystem/)
- [D1 IndexStore](/docs/index-stores/d1/)
- [JDBC IndexStore](/docs/index-stores/jdbc/)
