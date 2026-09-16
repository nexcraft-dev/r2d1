<p align="center">
  <img src="docs/assets/r2d1-logo.png" alt="R2D1 logo" width="120">
</p>

<h1 align="center">R2D1</h1>

<p align="center">
  A Java document store using Cloudflare R2 for storage and D1 for indexing,
  filtering, sorting, and pagination.
</p>

R2D1 is a framework-independent Java library that combines an authoritative `DocumentStore` with a
rebuildable `IndexStore` for document storage and indexed queries. Cloudflare R2/D1 and local
filesystem/JDBC combinations use the same persistence orchestration.

Official website: [r2d1.nexcraft.dev](https://r2d1.nexcraft.dev)

The storage model has two parts:

- **The configured `DocumentStore` is authoritative for document content and logical existence.**
- **The configured `IndexStore` is a rebuildable projection used to find documents.**

R2D1 exposes a limited query model. It is not a SQL database or ORM.

## Dependencies

The latest stable Maven Central surface contains four published artifacts. The base `r2d1` artifact
contains the Core API and the Cloudflare R2 and D1 implementations. Filesystem, JDBC, and Micronaut
are additional published integrations. Spring Boot is the upcoming integration for `1.7.0`, split
into a published auto-configuration module and a convenience starter, and is not yet in the latest
stable release:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1:<version>")
    implementation("dev.nexcraft:r2d1-filesystem:<version>")
    implementation("dev.nexcraft:r2d1-jdbc:<version>")
    implementation("dev.nexcraft:r2d1-micronaut:<version>")
}
```

The upcoming Spring Boot integration will use the starter:

```kotlin
implementation("dev.nexcraft:r2d1-spring-boot-starter:1.7.0")
```

It is not available from Maven Central until the explicit `v1.7.0` release completes.

The equivalent Maven coordinates are:

```xml
<dependency>
  <groupId>dev.nexcraft</groupId>
  <artifactId>r2d1-filesystem</artifactId>
  <version>${r2d1.version}</version>
</dependency>
<dependency>
  <groupId>dev.nexcraft</groupId>
  <artifactId>r2d1</artifactId>
  <version>${r2d1.version}</version>
</dependency>
<dependency>
  <groupId>dev.nexcraft</groupId>
  <artifactId>r2d1-jdbc</artifactId>
  <version>${r2d1.version}</version>
</dependency>
<dependency>
  <groupId>dev.nexcraft</groupId>
  <artifactId>r2d1-micronaut</artifactId>
  <version>${r2d1.version}</version>
</dependency>
```

The upcoming Spring Boot coordinates are `dev.nexcraft:r2d1-spring-boot-starter:1.7.0` for normal
applications and `dev.nexcraft:r2d1-spring-boot-autoconfigure:1.7.0` for custom starter composition;
neither is yet a stable Maven Central dependency.

Declare only the integrations used by an application. JDBC drivers remain application-provided
and are not bundled by `r2d1-jdbc`.

## Architecture

```text
R2D1Collection / Query              synchronous public API
              │
              ▼
Synchronous collection façade      blocking boundary
              │
              ▼
CompletionStage orchestration      asynchronous composition
              │
       ┌──────┴──────┐
       ▼             ▼
DocumentStore     IndexStore        technology-neutral SPI
       │             │
       ▼             ├──────────────┐
Cloudflare R2         ▼              ▼
Documents        Cloudflare D1   H2/HSQLDB/SQLite via JDBC
                 Indexes         Local indexes
       │
       └── FilesystemDocumentStore
```

The public collection API is synchronous. Storage I/O is composed asynchronously, and
`StageSupport.await()` is used only at the synchronous boundary. The storage SPI exposes
`CompletionStage` without leaking AWS or Cloudflare transport types and does not define an
executor, callback thread, cancellation guarantee, or timeout policy.

R2D1 does not have a global thread-mode switch. The Core SPI does not own an executor. D1 uses
Java's asynchronous HTTP client and R2 uses the AWS SDK's asynchronous Netty client, so neither
adapter creates an R2D1-managed worker pool. The optional JDBC and filesystem adapters perform
blocking work through caller-owned execution resources; JDBC uses its bounded `JdbcExecution`, and
`FileSystemDocumentStore` accepts a caller-owned executor.

Persistence operations follow these paths:

- `put`: serialize the document, write the authoritative `DocumentStore`, then upsert the index row.
- `get`: read the authoritative `DocumentStore` only.
- `query`: query the `IndexStore`, then fetch matching documents concurrently while preserving index order.
- `delete`: delete the authoritative document, then delete the derived index row.

The two stores do not share an atomic transaction. A successful authoritative mutation followed by
a failed index mutation is reported as a partial failure and may require an explicit index rebuild.

## Design Goals

The project focuses on:

- Simple CRUD operations
- Indexed filtering
- Indexed sorting
- Cursor-based pagination
- Asynchronous storage orchestration
- A small and predictable query API
- A framework-independent Java API
- Optional integrations for frameworks such as Spring

## Out of Scope

R2D1 does not provide:

- A relational database
- A general-purpose SQL abstraction
- An ORM
- A replacement for complex query engines
- A system for joins or arbitrary aggregations

Queries can use only fields that have been explicitly indexed.

## Storage and Query Model

### The DocumentStore owns the document

The complete document is stored in the configured `DocumentStore`. In the Cloudflare deployment
this is R2; the filesystem adapter stores one canonical file per document.

D1 should contain only the metadata and indexed fields necessary to locate and query documents.

```text
DocumentStore
└── users/01JXYZ...
    └── Complete document

D1
└── users
    ├── document_id
    ├── country
    ├── status
    └── createdAt
```

### The IndexStore is rebuildable

The index is a projection over documents stored in the authoritative `DocumentStore`, not an
authoritative document store. It can be rebuilt from authoritative documents when necessary.

### Query constraints

R2D1 does not query unindexed document fields.

A query such as:

```java
users.query()
    .where("country").eq("NZ")
    .sortBy("createdAt", SortDirection.DESC)
    .limit(20)
    .fetch();
```

is valid only when the queried and sorted fields have been configured appropriately for indexing.

R2D1 does not download R2 objects for filtering in application memory.

## R2D1 Java API

The `r2d1` artifact defines the framework-independent contracts and includes the Cloudflare R2 and
D1 adapters shown below. Applications supply a `DocumentCodec`, keeping domain serialization
independent of any specific JSON library.

```java
DocumentStore documentStore = configuredDocumentStore;
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

User document = applicationUser;
users.put(document);

Optional<User> storedUser = users.get("user-123");

Page<User> page = users.query()
    .where("country").eq("NZ")
    .sortBy("createdAt", SortDirection.DESC)
    .limit(20)
    .fetch();

users.delete("user-123");

// Run explicitly during a maintenance window when the D1 projection must be recovered.
users.rebuildIndex();
```

`R2DocumentStore` and `D1IndexStore` implement `AutoCloseable`. The application owns adapter and
executor lifecycles; `PersistenceCollectionFactory` does not close them.

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

The configured `DocumentStore` is authoritative for content and logical existence. The
`IndexStore` is a disposable materialized projection that may become temporarily inconsistent when
the authoritative operation succeeds and the following index operation fails. R2D1 exposes an
explicit collection-level recovery operation for this case:

```java
R2D1Collection<User> users = db.collection(User.class);
users.rebuildIndex();
```

The rebuild lists authoritative document keys in bounded pages, loads each document, derives its
index entry through the normal metadata path, clears only the collection's index rows, and writes
the replacement rows. Index tables, columns, and indexes are preserved. Missing index rows are
restored and stale rows are removed, including when the authoritative collection is empty.

Rebuilds are synchronous at the public API and asynchronously composed internally. They are
idempotent for an unchanged authoritative collection but are not atomic across the two stores. A
failure after index rows are cleared can leave the projection incomplete; resolve the failure and
run `rebuildIndex()` again. Run this maintenance operation while application writes are paused.

The global mutation contract is the same for R2 + D1, R2 + JDBC, Filesystem + D1, and Filesystem +
JDBC. PUT and DELETE are authoritative-store first. If index cleanup fails after an authoritative
DELETE, `DELETE` reports `PersistenceException.PartialFailure`; GET still reports the document as
absent, while a stale index row causes query hydration to fail explicitly with
`PersistenceException.InconsistentState`. R2D1 does not silently skip the row or change page and
cursor semantics. A rebuild scans the authoritative store, so a physically deleted document is not
resurrected.

## Modules

R2D1 is organized as a modular project.

```text
r2d1
    Core API, Cloudflare R2 DocumentStore, and Cloudflare D1 IndexStore

r2d1-filesystem
    Filesystem DocumentStore with atomic canonical-file replacement

r2d1-jdbc
    Optional JDBC IndexStore adapter with bounded execution and built-in H2, HSQLDB, and SQLite support

r2d1-micronaut
    Micronaut 5 configuration and dependency injection integration

r2d1-spring-boot-autoconfigure
    Upcoming Spring Boot 4 auto-configuration for the same framework-neutral API

r2d1-spring-boot-starter
    Upcoming Spring Boot 4 convenience starter that depends on the auto-configuration module

r2d1-integration-tests
    Opt-in live tests against dedicated Cloudflare R2 and D1 resources
```

Integration tests are grouped by purpose: Cloudflare resource scenarios use
`dev.nexcraft.r2d1.integration.cloudflare`, end-to-end consistency flows use
`dev.nexcraft.r2d1.integration.persistence`, and reusable fixtures live under
`dev.nexcraft.r2d1.integration.support`.

Framework-specific integrations remain separate from the base library. The optional Micronaut 5
module publishes `dev.nexcraft:r2d1-micronaut`, and the upcoming Spring Boot integration will
publish `dev.nexcraft:r2d1-spring-boot-autoconfigure` and
`dev.nexcraft:r2d1-spring-boot-starter`. Both create the top-level `R2D1` facade from application
beans and selected adapters without changing the public API or SPI.

Implementation details are grouped below the supported public packages. Base persistence
orchestration uses `dev.nexcraft.r2d1.internal.persistence`; the built-in D1 adapter separates metadata,
SQL, transport, and schema code under `dev.nexcraft.r2d1.d1.internal`; and JDBC keeps metadata and
database mechanics under `dev.nexcraft.r2d1.jdbc.internal`. These internal packages are excluded
from the supported API and may change between releases.

Public Java packages use JSpecify `@NullMarked` semantics. Nullable API positions, such as a final
page's absent `nextCursor`, are declared explicitly with `@Nullable`.

The base API does not depend on Micronaut or Spring.

## Filesystem Module

The optional `r2d1-filesystem` artifact provides `FileSystemDocumentStore`:

```java
ExecutorService filesystemExecutor = Executors.newFixedThreadPool(4);
FileSystemDocumentStore documents =
    new FileSystemDocumentStore(Path.of("/var/lib/my-app/r2d1"), filesystemExecutor);
```

The application owns and closes `filesystemExecutor`. Each collection is one encoded directory;
each document is one encoded `<id>.json` canonical file. PUT writes a unique temporary file in the
same directory and publishes it with `ATOMIC_MOVE` plus replacement. If the provider cannot provide
that atomic publication, the operation fails and the previous canonical file remains in place; no
unsafe delete-then-move fallback is attempted. Temporary and orphan files are ignored by GET and
listing, and a process crash may leave an orphan temporary file. Atomic visibility is not an fsync
or power-loss durability guarantee.

The filesystem adapter does not provide version history, lost-update prevention, CAS, locks, retries,
or replication. Concurrent complete PUTs have last-publication-wins behavior according to the
underlying filesystem's atomic-move ordering. It follows the same authoritative DocumentStore and
rebuildable IndexStore contract as R2, so it can be combined with D1 or JDBC without a
filesystem-specific consistency rule.

## D1 Schema Initialization

The `r2d1` artifact uses the Cloudflare D1 REST API through Java's reusable asynchronous `HttpClient`
and Avaje JSON-B generated adapters for the REST protocol. A document type's schema must be
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

## JDBC Module

The optional `r2d1-jdbc` module adapts blocking JDBC databases to the asynchronous `IndexStore`
contract through a bounded execution resource. It supports persistent embedded and remote/server
H2 and HSQLDB databases plus local embedded SQLite files. The caller-provided `DataSource` defines
the endpoint and owns credentials, TLS, pooling, and server lifecycle; JDBC execution mode is
independent of deployment topology. The module detects the database from JDBC metadata, keeps
database-specific behavior behind an internal dialect boundary, and does not bundle a JDBC driver.
The JDBC index remains a rebuildable projection rather than an authoritative document store.

See the [JDBC module guide](r2d1-jdbc/README.md) for supported databases, Gradle and Maven
dependencies, database configuration, lifecycle ownership, schema behavior, and query semantics.

See the [Micronaut 5 module guide](r2d1-micronaut/README.md) for dependencies, the complete
configuration contract, override rules, lifecycle ownership, and native-image requirements.

See the [Spring Boot auto-configuration guide](r2d1-spring-boot-autoconfigure/README.md) and
[starter guide](r2d1-spring-boot-starter/README.md) for the upcoming `1.7.0` integration,
configuration contract, override rules, lifecycle ownership, and optional adapter boundaries.

See the [release guide](docs/releasing.md) for Maven Central coordinates, release tags, required
GitHub secrets, and the automated Central Portal publishing workflow.

## Project Status

R2D1 is under active development. The core API, R2, D1, filesystem, persistence orchestration, and
the H2, HSQLDB, and SQLite JDBC adapters are available but remain unstable. Explicit index recovery
through `rebuildIndex()` is available, but automatic reconciliation and background repair are not.
Other JDBC databases are not yet supported. The Spring Boot integration is targeted for `1.7.0` and
is not part of the latest stable release. Module internals may change before the first release.

## Requirements

- Java 21+
- Java 25+ for `r2d1-micronaut`; `r2d1` and `r2d1-jdbc` remain Java 21 compatible
- Java 21+ and Spring Boot 4.0.6 for the upcoming `r2d1-spring-boot-starter` integration
- Adapter-specific infrastructure:
  - Cloudflare account and R2 bucket for the built-in R2 adapter
  - Cloudflare account and D1 database for the built-in D1 adapter
  - A writable filesystem root and caller-owned blocking-I/O executor for the filesystem adapter
  - Application-provided H2, HSQLDB, or SQLite driver and `DataSource` for the `r2d1-jdbc` backend

## Development

Build and verify the complete project from the repository root:

```shell
./gradlew clean check
./gradlew javadoc
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

Create the dedicated R2 bucket and D1 database manually before the first run. The test suite does
not provision or delete Cloudflare resources.

The integration-test task reads the process environment directly and does not load a `.env` file.
If you keep the variables in a local file, source it before running Gradle:

```shell
set -a
source /absolute/path/to/r2d1-integration.env
set +a
```

This repository does not ignore `.env` files. Keep credential files outside the checkout or add
them to your local Git exclude configuration, and never commit Cloudflare credentials.

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
not exist, and later runs validate and reuse those schemas.

## License

R2D1 is licensed under the [Apache License 2.0](LICENSE).

## Project

R2D1 is developed under **NexCraft**.

- GitHub Organization: `nexcraft-dev`
- Website: `nexcraft.dev`
