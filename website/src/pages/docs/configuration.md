---
layout: ../../layouts/DocsLayout.astro
title: Configuration
description: Public artifacts, storage combinations, resource ownership, and runtime boundaries.
---

## Public modules

The latest stable release exposes six artifacts, including the Spring Boot integration pair published
in `1.7.0`:

| Module | Coordinate | Provides |
| --- | --- | --- |
| `R2D1` | `dev.nexcraft:r2d1` | Common API, Cloudflare R2 `DocumentStore`, and Cloudflare D1 `IndexStore` |
| `R2D1-FILESYSTEM` | `dev.nexcraft:r2d1-filesystem` | Filesystem `DocumentStore` |
| `R2D1-JDBC` | `dev.nexcraft:r2d1-jdbc` | H2, HSQLDB, and SQLite `IndexStore` support |
| `R2D1-MICRONAUT` | `dev.nexcraft:r2d1-micronaut` | Micronaut 5 configuration and bean integration |
| `R2D1-SPRING-BOOT-AUTOCONFIGURE` | `dev.nexcraft:r2d1-spring-boot-autoconfigure` | Spring Boot 4 auto-configuration |
| `R2D1-SPRING-BOOT-STARTER` | `dev.nexcraft:r2d1-spring-boot-starter` | Spring Boot 4 convenience starter |

The following stable dependencies are rendered with the website's current stable release version:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1:{{latestStableVersion}}")
    implementation("dev.nexcraft:r2d1-filesystem:{{latestStableVersion}}")
    implementation("dev.nexcraft:r2d1-jdbc:{{latestStableVersion}}")
    implementation("dev.nexcraft:r2d1-micronaut:{{latestStableVersion}}")
    implementation("dev.nexcraft:r2d1-spring-boot-starter:{{latestStableVersion}}")
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

See the adapter pages for credentials, schema initialization, and lifecycle details:

- [R2 DocumentStore](/docs/document-stores/r2/)
- [Filesystem DocumentStore](/docs/document-stores/filesystem/)
- [D1 IndexStore](/docs/index-stores/d1/)
- [JDBC IndexStore](/docs/index-stores/jdbc/)
