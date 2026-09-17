# R2D1 Filesystem

`r2d1-filesystem` provides `FileSystemDocumentStore`, a framework-neutral implementation of the
asynchronous R2D1 `DocumentStore` SPI. It is an authoritative document store that can be paired
with either the built-in D1 or optional JDBC `IndexStore`.

## Dependency

Gradle:

```kotlin
implementation("dev.nexcraft:r2d1-filesystem:<version>")
```

Maven:

```xml
<dependency>
  <groupId>dev.nexcraft</groupId>
  <artifactId>r2d1-filesystem</artifactId>
  <version>${r2d1.version}</version>
</dependency>
```

## Usage

Filesystem I/O is blocking. Supply an application-owned executor intended for blocking work and
close that executor when the application no longer uses the store:

```java
ExecutorService executor = Executors.newFixedThreadPool(4);
FileSystemDocumentStore documents =
    new FileSystemDocumentStore(Path.of("/var/lib/my-app/r2d1"), executor);

R2D1 database =
    R2D1.builder()
        .collectionFactory(
            new PersistenceCollectionFactory(
                documents, indexStore, codec, collectionInitializer))
        .build();
```

The adapter does not create, own, close, or globally share an executor. A bounded caller-owned
executor is recommended. The adapter never uses `ForkJoinPool.commonPool()` and does not add retry,
lock, or scheduler behavior.

Each store defaults to 8 active and 32 pending operations. Pass a `BackpressureConfig` as the third
constructor argument to choose different limits:

```java
import dev.nexcraft.r2d1.BackpressureConfig;

FileSystemDocumentStore documents =
    new FileSystemDocumentStore(
        Path.of("/var/lib/my-app/r2d1"), executor, new BackpressureConfig(4, 16));
```

Every operation is admitted before it is submitted to the caller's executor. When both limits are
full, it fails immediately with `AdmissionRejectedException`. Canceling a returned stage does not
free capacity before a running filesystem operation completes. Admission capacity is separate from
executor capacity and ownership. See the
[configuration guide](https://r2d1.nexcraft.dev/docs/configuration/) for framework settings and
fan-out behavior.

## Storage model

The root contains one encoded directory per collection and one encoded canonical file per document:

```text
<root>/<encoded-collection>/<encoded-document-id>.json
```

Collection names and document IDs are percent-encoded as UTF-8 path segments. Separators,
absolute-path text, traversal components, and platform-reserved names cannot escape the root or
collide with another valid identity. The mapping is deterministic and reversible for canonical
files.

GET reads only a canonical regular file. LIST recognizes only canonical `.json` files and ignores
temporary, orphan, symlink, and unrelated files. A missing GET fails with
`DocumentNotFoundException`; DELETE is idempotent for a missing canonical file.

## Atomic replacement

PUT creates a unique temporary file in the collection directory, writes the complete serialized
document, closes the file, and publishes it with:

```text
ATOMIC_MOVE + REPLACE_EXISTING
```

The source and target are deliberately in the same collection directory. If the provider cannot
perform an atomic replacement, the operation fails through `StorageException.Operation`; the
adapter never falls back to delete-then-move or another unsafe sequence. A failed operation best
effort removes its own temporary file. A process crash can leave an orphan temporary file, which
does not affect logical visibility and is not removed automatically at startup.

Atomic visibility means a reader observes a complete old or new canonical file. It is not an fsync
or power-loss durability guarantee. Directory and file metadata are not forced to stable storage by
this adapter. Provider and operating-system behavior for open read handles, replacement of an
existing target, and network filesystems remains part of the deployment capability contract. The
configured provider must support atomic replacement of an existing target in one directory.

Concurrent PUT operations do not use JVM or distributed locks. Each operation has its own temporary
file; the final complete document follows the underlying provider's publication ordering. R2D1 does
not provide lost-update prevention for concurrent read-modify-write operations.

## Persistence consistency

The filesystem adapter follows the same global contract as every other supported combination:

- PUT writes the authoritative `DocumentStore` first, then upserts the derived index.
- DELETE removes the authoritative document first, then removes its index row.
- A failed second stage is reported as `PersistenceException.PartialFailure`; no automatic rollback
  or retry is attempted.
- GET uses authoritative storage only. After a partial DELETE it reports the document as absent.
- QUERY preserves index ordering and page/cursor semantics. If a stale index row cannot be hydrated,
  the query fails explicitly with `PersistenceException.InconsistentState`; it does not silently
  skip the row or fetch an unbounded replacement page.
- `rebuildIndex()` scans the authoritative filesystem documents in bounded pages. Because a
  successful authoritative DELETE removes the canonical file before index cleanup, rebuilding does
  not resurrect a document after a partial index-delete failure.

These semantics are shared by R2 + D1, R2 + JDBC, Filesystem + D1, and Filesystem + JDBC. The
filesystem adapter does not add a special ordering, tombstone, lock, distributed transaction, or
compensation mechanism.

## Lifecycle and scope

`FileSystemDocumentStore` does not implement version history, MVCC, CAS, ETags, optimistic or
pessimistic locking, replication, background repair, or automatic cleanup. The application owns the
root directory, filesystem provider, executor, backup policy, and any maintenance window used for
`rebuildIndex()`.
