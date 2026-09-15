# ADR 002: Authoritative document and derived-index consistency

- Status: Accepted
- Date: 2026-09-15

## Context

R2D1 coordinates an authoritative `DocumentStore` and a derived, rebuildable `IndexStore` without
a distributed transaction. The supported pairings are R2 + D1, R2 + JDBC, Filesystem + D1, and
Filesystem + JDBC. A failure between the two storage operations can leave their physical state
temporarily different. The consistency rule must therefore be defined in the core orchestration,
not in one adapter.

## Decisions

### PUT ordering

PUT remains:

```text
DocumentStore.put -> IndexStore.upsert
```

The authoritative document is available before its projection is written. If the index upsert
fails, R2D1 reports `PersistenceException.PartialFailure` and leaves the document in place so an
explicit `rebuildIndex()` can restore the projection. Reversing PUT would leave an index row that
cannot be hydrated when the authoritative write fails.

### DELETE ordering

DELETE remains:

```text
DocumentStore.delete -> IndexStore.delete
```

This is the simplest contract compatible with authoritative rebuilds and the existing API. If the
authoritative delete succeeds and index cleanup fails, DELETE reports `PersistenceException.PartialFailure`.
No rollback or retry is attempted.

### Logical existence

`DocumentStore` is authoritative for both complete document content and logical existence. An index
row is only a discovery and indexed-field projection; it never makes a missing document logically
exist. `get(id)` reads the document store only and returns empty after an authoritative delete.

### QUERY divergence

QUERY obtains ordered IDs from `IndexStore` and hydrates them from `DocumentStore` concurrently. A
missing authoritative document for an index ID is an explicit `PersistenceException.InconsistentState`.
R2D1 does not silently skip the ID or keep fetching beyond the requested limit, because doing so
would require a new page/cursor contract and could change ordering, page size, or tie-breaking.

### PartialFailure

`PartialFailure` preserves the original second-stage cause and the affected `DocumentKey`. It is
not converted into success, a silent recovery, or a compensating write. The exception distinguishes
an incomplete two-store mutation from a single-adapter `StorageException`.

### rebuildIndex()

`rebuildIndex()` lists the authoritative document store in bounded pages, hydrates each document,
derives its index entry, clears the collection's derived rows, and upserts the replacement rows. It
is explicit, rerunnable, asynchronous internally, synchronous only at the public boundary, and
non-atomic. Writes should be paused during maintenance.

The mandatory partial-delete scenarios are coherent under this contract:

| Scenario | Result |
| --- | --- |
| Index cleanup succeeds and authoritative delete succeeds | Both stores are absent; DELETE succeeds. |
| Authoritative delete succeeds and index cleanup fails | GET is absent; QUERY fails explicitly if the stale row remains; DELETE reports `PartialFailure`. |
| `rebuildIndex()` after that partial failure | The canonical document is absent, so clearing and rebuilding cannot resurrect it. |

### Why no index-first DELETE

Index-first DELETE would make a failed second stage leave a physical orphan that is absent from the
index but still returned by authoritative GET. A rebuild based on the authoritative scan would then
reinsert it unless persistent deletion state were added. Tombstones or another deletion ledger would
be a new lifecycle and cleanup model, so the phase does not introduce them.

### Filesystem behavior

The filesystem adapter follows this same contract. Its canonical file is authoritative; temporary
files and orphan temporary files are not documents. Atomic replacement is an adapter-level
visibility guarantee and does not change PUT/DELETE orchestration. Filesystem-specific locks,
tombstones, retries, or query filtering are not permitted.

## Consequences

- All four supported adapter pairings expose one persistence semantics.
- A failed index cleanup is visible and requires explicit recovery rather than hidden compensation.
- Query pagination remains strict: stale IDs fail rather than being silently removed.
- Recovery cannot resurrect an authoritative document deleted before index cleanup failed.
- The contract does not provide distributed transactions, automatic retries, lost-update prevention,
  or power-loss durability.
