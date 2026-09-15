---
layout: ../../layouts/DocsLayout.astro
title: Consistency and Recovery
description: Understand R2D1's authoritative document, derived index, partial failure, and rebuild contract.
---

R2D1 coordinates two stores without pretending they form one distributed transaction. The
`DocumentStore` is authoritative for content and logical existence. The `IndexStore` is a
rebuildable materialized projection.

## Mutation ordering

Both mutation paths update the authoritative document first:

```text
PUT:    DocumentStore.put    -> IndexStore.upsert
DELETE: DocumentStore.delete -> IndexStore.delete
```

This ordering makes an authoritative scan sufficient for recovery. Reversing delete would leave a
physical document that a rebuild could discover and insert again.

## PartialFailure

If the first operation succeeds and the second operation fails, R2D1 raises
`PersistenceException.PartialFailure`. The exception retains the affected `DocumentKey` and the
original second-stage cause. There is no automatic rollback, retry, or compensating write.

For example, when an index cleanup fails after an authoritative delete:

- `get(id)` reports the document as absent because it reads the document store;
- a stale index row can make a query fail with `PersistenceException.InconsistentState`; and
- the failed delete is visible as `PartialFailure`.

R2D1 does not silently skip the stale row. Skipping it would change the requested page size and
cursor semantics.

## Rebuild the projection

Call `rebuildIndex()` on the collection after resolving the underlying failure:

```java
users.rebuildIndex();
```

The operation lists authoritative document keys in bounded pages, loads each document, derives its
index entry through normal metadata, clears only the collection's derived rows, and writes the
replacement rows. It preserves tables, columns, and managed indexes.

The public method is synchronous, but the store operations are composed asynchronously internally.
It is idempotent for an unchanged authoritative collection and is safe to rerun after a failure.
It is not atomic: a failure after rows are cleared can leave the projection incomplete. Pause
application writes to the collection while rebuilding, resolve the failure, and run it again.

## What recovery does not provide

The consistency contract does not provide a distributed transaction, automatic background repair,
automatic retries, concurrent rebuild coordination, lost-update prevention, or power-loss durability.
Those are deployment and application responsibilities outside the two-store orchestration.
