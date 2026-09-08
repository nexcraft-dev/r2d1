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
- Asynchronous storage orchestration
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
Sync façade                   the only blocking boundary
              │
              ▼
Async orchestration           CompletionStage composition
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
Sync façade
        │
        ▼
Async orchestration
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

`PersistenceCollectionFactory` supplies application-to-SPI translation, orchestration, and the
synchronous façade. The D1 adapter implements the asynchronous index SPI, but no cross-store atomic
transaction is implied by either storage adapter.

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

The core module defines the framework-independent contracts shown below. The R2 document adapter,
D1 index adapter, and cross-store collection factory are available. Applications supply a
`DocumentCodec` so domain serialization remains independent of any JSON library.

```java
R2DocumentStore documentStore = configuredR2DocumentStore;
D1IndexStore indexStore = configuredD1IndexStore;
DocumentCodec documentCodec = applicationDocumentCodec;

R2D1 db = R2D1.builder()
    .collectionFactory(new PersistenceCollectionFactory(
        documentStore,
        indexStore,
        documentCodec,
        indexStore::initialize))
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

// Run explicitly during a maintenance window when the D1 projection must be recovered.
users.rebuildIndex();
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

## Consistency Recovery

R2 is the authoritative document store. D1 is a disposable materialized index that may become
temporarily inconsistent when an R2 write or delete succeeds and the following D1 operation fails.
R2D1 exposes an explicit collection-level recovery operation for this case:

```java
R2D1Collection<User> users = db.collection(User.class);
users.rebuildIndex();
```

The rebuild lists R2 document keys in bounded pages, loads each authoritative document, derives its
index entry through the normal metadata path, clears only the collection's D1 rows, and writes the
replacement rows. D1 tables, columns, and SQLite indexes are preserved. Missing D1 rows are restored
and stale D1 rows are removed, including when the authoritative collection is empty.

Rebuilds are synchronous at the public API and asynchronously composed internally. They are
idempotent for an unchanged R2 collection but are not atomic across R2 and D1. A failure after the D1
rows are cleared can leave the index incomplete; resolve the failure and run `rebuildIndex()` again.
Run this maintenance operation while application writes to the collection are paused. R2D1 does not
add background reconciliation, automatic retries, distributed locks, queues, or Workers.

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

`PersistenceCollectionFactory` invokes this initialization through the supplied collection
initializer and waits for it at the synchronous collection boundary. The D1 adapter itself does not
block, create executors, retry requests, serialize complete documents, or manage Cloudflare
infrastructure.

## Project Status

🚧 **R2D1 is currently in the early design and development stage.**

The first core API contracts, the R2 document adapter, the D1 index adapter, and synchronous
persistence orchestration are available but remain unstable. Automated reconciliation and background
index repair are not implemented yet, and module internals may change significantly before the
first release.

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

### Cloudflare integration tests

Live integration tests are isolated in the `r2d1-integration-tests` module and are never run by
the standard `build` or `check` tasks. They must use an R2 bucket and D1 database dedicated to
R2D1 integration testing. Do not point them at production resources or resources shared with an
application.

Set every required environment variable before running the opt-in task:

```shell
export R2D1_IT_R2_ENDPOINT="https://<account-id>.r2.cloudflarestorage.com"
export R2D1_IT_R2_ACCESS_KEY_ID="<dedicated-r2-access-key-id>"
export R2D1_IT_R2_SECRET_ACCESS_KEY="<dedicated-r2-secret-access-key>"
export R2D1_IT_R2_BUCKET_NAME="<dedicated-r2-bucket>"
export R2D1_IT_D1_ACCOUNT_ID="<cloudflare-account-id>"
export R2D1_IT_D1_DATABASE_ID="<dedicated-d1-database-id>"
export R2D1_IT_D1_API_TOKEN="<dedicated-d1-api-token>"
export R2D1_IT_CONFIRM_DEDICATED_RESOURCES="true"

GRADLE_USER_HOME=/tmp/r2d1-gradle-home \
  ./gradlew :r2d1-integration-tests:integrationTest
```

An explicit integration-test run fails when any variable is missing or the dedicated-resource
confirmation is not exactly `true`. The tests never print credential values. They delete only
objects and rows in their versioned test collections before and after each scenario; D1 tables,
columns, and SQLite indexes are retained. The first run creates the managed D1 schemas when they do
not exist, and later runs validate and reuse those schemas. Resource provisioning and deletion are
deliberately outside the test suite.

## License

R2D1 is licensed under the [Apache License 2.0](LICENSE).

## Project

R2D1 is developed under **NexCraft**.

- GitHub Organization: `nexcraft-dev`
- Website: `nexcraft.dev`
