---
layout: ../../../layouts/DocsLayout.astro
title: Filesystem DocumentStore
description: Store authoritative R2D1 documents in a local filesystem with atomic canonical-file replacement.
---

`FileSystemDocumentStore` is a framework-neutral asynchronous `DocumentStore` backed by blocking
filesystem operations dispatched to an executor supplied by the application.

## Dependency and construction

```kotlin
implementation("dev.nexcraft:r2d1-filesystem:{{latestStableVersion}}")
```

The constructor accepts a root directory and an executor for blocking I/O:

```java
import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.filesystem.FileSystemDocumentStore;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

ExecutorService filesystemExecutor = Executors.newFixedThreadPool(4);
FileSystemDocumentStore documents =
    new FileSystemDocumentStore(
        Path.of("/var/lib/my-app/r2d1"), filesystemExecutor, new BackpressureConfig(4, 8));
```

The default per-store admission budget is 8 active and 32 pending operations. Pass a
`BackpressureConfig` to set a different budget, or use Spring Boot's
`r2d1.filesystem.backpressure.*` properties. The adapter admits each filesystem operation before
submitting it to the executor. It does not create, resize, close, or globally share the executor.
Stop admitting work and close the executor after the store is no longer used.

When all active and pending slots are occupied, an operation fails immediately with
`AdmissionRejectedException`. Cancelling the returned stage does not free a slot while its
filesystem operation is still running. The executor's own capacity remains a separate limit.

## On-disk layout

The root contains one encoded directory per collection and one encoded canonical file per document:

```text
<root>/<encoded-collection>/<encoded-document-id>.r2d1
```

Collection names and identifiers are encoded as UTF-8 path segments. `list` recognizes canonical
`.r2d1` files only. Temporary files, orphan temporary files, symlinks, and unrelated files are not
documents. A missing canonical file makes `get` fail with `DocumentNotFoundException`; deleting a
missing file is idempotent.

## Atomic replacement

`put` writes the complete serialized content to a unique temporary file in the collection directory,
closes it, and publishes it with `ATOMIC_MOVE + REPLACE_EXISTING`. If the filesystem provider
cannot replace the target atomically, the operation fails with `StorageException.Operation`; the
adapter does not fall back to delete-then-move.

A process crash can leave an orphan temporary file. It does not change logical visibility and is not
removed automatically at startup. Atomic visibility is not an `fsync` or power-loss durability
guarantee; directory and file metadata are not forced to stable storage by this adapter.

Concurrent puts use separate temporary files and the provider's publication ordering. R2D1 does not
provide lost-update prevention for concurrent read-modify-write operations, JVM locks, distributed
locks, version history, MVCC, CAS, or ETags.

## Pair it with an index

The filesystem store follows the same ordering as R2:

```text
DocumentStore.put    -> IndexStore.upsert
DocumentStore.delete -> IndexStore.delete
```

Use [D1](/docs/index-stores/d1/) or [JDBC](/docs/index-stores/jdbc/) as the rebuildable index. See
[Consistency and Recovery](/docs/consistency/) for the behavior when the second operation fails.
