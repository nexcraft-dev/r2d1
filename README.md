# R2D1

> A lightweight Java document store powered by Cloudflare R2 for storage and D1 for indexing, filtering, sorting, and pagination.

R2D1 is a Java library that combines **Cloudflare R2** and **Cloudflare D1** to provide a simple document-oriented data store.

The idea is simple:

- **R2 stores the actual documents.**
- **D1 stores the indexes required to find them.**

R2D1 is intentionally designed around a small and predictable query model rather than trying to provide a full SQL database or ORM.

## Architecture

```text
                       R2D1
                         │
              ┌──────────┴──────────┐
              │                     │
              ▼                     ▼
       Cloudflare D1          Cloudflare R2
       ─────────────          ─────────────
       Indexes                Documents
       Filtering              JSON / Data
       Sorting
       Pagination
```

R2 acts as the primary document storage layer, while D1 provides the metadata and indexes necessary for efficient document discovery.

## Goals

R2D1 aims to provide:

- Simple CRUD operations
- Indexed filtering
- Indexed sorting
- Cursor-based pagination
- Asynchronous index updates
- A small and predictable query API
- A framework-independent Java API
- Optional integrations for frameworks such as Spring

## Non-Goals

R2D1 is not intended to be:

- A relational database
- A general-purpose SQL abstraction
- An ORM
- A replacement for complex query engines
- A system for joins or arbitrary aggregations

Queries are intentionally limited to fields that have been explicitly indexed.

## Design Philosophy

### Technology-neutral storage SPI

The core module separates authoritative document bytes from the derived index without exposing
vendor-specific concepts. The user-facing API remains synchronous while storage I/O is modeled as
an asynchronous contract:

```text
R2D1Collection / Query        synchronous public API
              │
              ▼
Future sync façade            the only blocking boundary
              │
              ▼
Future async orchestration    CompletionStage composition
              │
       ┌──────┴──────┐
       ▼             ▼
DocumentStore     IndexStore  asynchronous storage SPI
```

Physical document locations are derived by adapters from `DocumentKey`; index entries do not keep
a competing storage reference. Index queries return document keys for later authoritative reads.
The two stores do not promise a shared atomic transaction, so future orchestration must tolerate
temporary inconsistency and allow the index to be rebuilt.

These contracts are available in `dev.nexcraft.r2d1.spi` and expose `CompletionStage`, leaving
`CompletableFuture` as an implementation detail. The SPI owns no executor or virtual thread and
makes no callback-thread, cancellation, or timeout guarantee. The R2 module implements the
authoritative `DocumentStore` with the AWS SDK v2 `S3AsyncClient` and its Netty transport:

```text
Synchronous public API
        │
        ▼
Future sync façade
        │
        ▼
Future async orchestration
        │
        ▼
DocumentStore
        │
        ▼
R2DocumentStore
        │
        ▼
S3AsyncClient / Netty
        │
        ▼
Cloudflare R2
```

Application-to-SPI translation, orchestration, and the sync façade are not implemented yet. The D1
adapter implements the asynchronous index SPI, but no cross-store atomic transaction is implied by
either storage adapter.

### R2 owns the document

The complete document is stored in Cloudflare R2.

D1 should contain only the metadata and indexed fields necessary to locate and query documents.

```text
R2
└── users/01JXYZ...
    └── Complete document

D1
└── users
    ├── document_id
    ├── country
    ├── status
    └── createdAt
```

### D1 is a rebuildable index

D1 is treated as an index over the documents stored in R2 rather than the authoritative document store.

This allows the indexing layer to be rebuilt from the underlying document data when necessary.

### Queries should be predictable

R2D1 deliberately avoids querying unindexed document fields.

A query such as:

```java
users.query()
    .where("country").eq("NZ")
    .sortBy("createdAt", SortDirection.DESC)
    .limit(20)
    .fetch();
```

is valid only when the queried and sorted fields have been configured appropriately for indexing.

R2D1 will not download large numbers of R2 objects and perform filtering in application memory.

## Core Java API

The core module defines the framework-independent contracts shown below. The R2 document adapter
and D1 index adapter are available, but the collection factory, orchestration, and serialization
are not included yet.

```java
R2D1 db = R2D1.builder()
    .collectionFactory(collectionFactory)
    .build();

R2D1Collection<User> users = db.collection(User.class);

users.put(user);

Optional<User> user = users.get("user-123");

Page<User> page = users.query()
    .where("country").eq("NZ")
    .sortBy("createdAt", SortDirection.DESC)
    .limit(20)
    .fetch();

users.delete("user-123");
```

Document metadata may be declared using annotations:

```java
@Document("users")
public class User {

    @Id
    private String id;

    @Index
    private String country;

    @Index
    private String status;

    @Index
    private Long createdAt;

    private String name;
}
```

Only indexed fields participate in filtering and sorting. The D1 v1 adapter supports `String`,
`Long`, `Double`, and `Boolean` index values and treats every `@Index` field as sortable. The
existing `sortable` annotation member remains for public API compatibility but does not restrict D1
sorting.

Filters are combined with logical AND and support only equality, inequality, and ordered
comparisons. A query requires a positive result limit and may contain one indexed-field sort and
one opaque continuation cursor. The storage adapter is responsible for validating field metadata
before executing a query.

## Modules

R2D1 is organized as a modular project.

```text
r2d1-core
    Core API and abstractions

r2d1-d1
    Cloudflare D1 integration

r2d1-r2
    Cloudflare R2 DocumentStore adapter using AWS SDK v2
```

Framework-specific integrations will remain separate from the core library.

Public Java packages use JSpecify `@NullMarked` semantics. Nullable API positions, such as a final
page's absent `nextCursor`, are declared explicitly with `@Nullable`.

Future modules may include:

```text
r2d1-spring
r2d1-spring-boot-starter
```

The core API will not depend on Spring.

### D1 index adapter

The D1 module uses the Cloudflare D1 REST API through Java's reusable asynchronous `HttpClient` and
uses Avaje JSON-B generated adapters for the REST protocol. A document type's schema must be
initialized before its index operations are used:

```java
D1IndexStore indexes =
    new D1IndexStore(new D1Config(accountId, databaseId, apiToken));

CompletionStage<Void> initialized = indexes.initialize(User.class);
```

The future orchestration layer will await that stage at the synchronous collection boundary. The
D1 adapter itself does not block, create executors, retry requests, serialize complete documents,
or manage Cloudflare infrastructure.

## Project Status

🚧 **R2D1 is currently in the early design and development stage.**

The first core API contracts, the R2 document adapter, and the D1 index adapter are available but
remain unstable. The consistency model, orchestration, and module internals are still being
developed and may change significantly before the first release.

## Requirements

- Java 21+
- Cloudflare account
- Cloudflare R2
- Cloudflare D1

## Development

Build and verify the complete project from the repository root:

```shell
./gradlew build
./gradlew check
```

Format Java sources with:

```shell
./gradlew spotlessApply
```

## License

R2D1 is licensed under the [Apache License 2.0](LICENSE).

## Project

R2D1 is developed under **NexCraft**.

- GitHub Organization: `nexcraft-dev`
- Website: `nexcraft.dev`
