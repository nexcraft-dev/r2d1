---
layout: ../../layouts/DocsLayout.astro
title: Core Concepts
description: The R2D1 document, collection, index, query, and cursor model.
---

## R2D1

R2D1 is a framework-independent Java library that coordinates two storage contracts:

- `DocumentStore` is authoritative for complete serialized documents and logical existence.
- `IndexStore` is a rebuildable projection containing document identifiers and explicitly indexed
  fields.

The split is deliberate. It lets an application pair R2 with D1, R2 with JDBC, the filesystem
with D1, or the filesystem with JDBC without changing the collection API.

## Documents and collections

A document type declares its collection name with `@Document`. One non-blank `String` member marked
with `@Id` supplies the document identifier. `@Index` marks fields that the index store must keep
for lookup and ordering.

The base library uses the Avaje JSON-B generated-adapter codec by default (`format=json`, codec id
`avaje-jsonb-3`). Document types must be adapter-capable. A collection can receive a custom
`DocumentCodec<T>` when an application needs a different format or serialization policy; the codec
is the boundary between a domain object and the `StoredDocument` bytes held by the authoritative
store.

## DocumentStore

The document store exposes asynchronous SPI operations for listing, putting, reading, and deleting
serialized documents. The public collection methods wait only at the synchronous facade. R2D1's R2
and filesystem implementations are document stores; neither is a query engine.

`get(id)` reads this store directly. An index row does not make a missing document logically exist.

## IndexStore

The index store receives one projection entry per document. Each entry contains the document key and
the values of the fields marked with `@Index`. D1 and JDBC implement this contract.

The index is disposable. `rebuildIndex()` scans authoritative documents, derives their index
entries, clears the collection's rows, and writes the replacement projection. The operation is
explicit and non-atomic; run it while writes to the collection are paused.

## Queries

`R2D1Collection.query()` returns an immutable query builder. A query can contain:

- zero or more comparisons against explicitly indexed fields;
- one indexed sort with `ASC` or `DESC` direction;
- one positive result limit; and
- one opaque continuation cursor from a previous page.

Multiple comparisons use logical AND semantics. The public API does not provide joins, arbitrary
SQL, OR predicates, full-text search, regular expressions, or client-side filtering.

## Cursor pagination

`fetch()` returns a `Page<T>`. `items()` contains the documents for the current page and
`nextCursor()` is non-null when another page is available. Pass that value to `after(...)` on a new
query with the same filters and sort.

The cursor belongs to the adapter that created it. Applications should treat it as an opaque string,
not decode or edit it. Adapter ordering includes a deterministic document identifier tie-breaker so
equal sort values do not make the page boundary ambiguous.
