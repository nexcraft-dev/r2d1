---
layout: ../../layouts/DocsLayout.astro
title: Querying
description: Filter, sort, and paginate R2D1 documents using explicitly indexed fields.
---

R2D1 queries the derived index first and then hydrates matching documents from authoritative storage.
It does not scan document content and filter it in application memory.

## Filter indexed fields

Start a comparison with `where(String)`. The field must be explicitly marked with `@Index` and the
adapter must support the supplied value type.

```java
Page<User> page =
    users.query()
        .where("country")
        .eq("NZ")
        .limit(20)
        .fetch();
```

The public query builder exposes these comparisons:

| Method | Meaning |
| --- | --- |
| `eq(value)` | Equal to the supplied value |
| `notEq(value)` | Not equal to the supplied value |
| `gt(value)` | Greater than the supplied value |
| `gte(value)` | Greater than or equal to the supplied value |
| `lt(value)` | Less than the supplied value |
| `lte(value)` | Less than or equal to the supplied value |

Every filter is combined with logical AND. Boolean fields support equality and inequality in the
built-in JDBC contract; ordered comparisons require an ordered value type.

## Sort one indexed field

Configure at most one sort:

```java
Page<User> page =
    users.query()
        .where("country")
        .eq("NZ")
        .sortBy("score", SortDirection.DESC)
        .limit(20)
        .fetch();
```

The sort field must be indexed and compatible with the adapter. The query API rejects a second
`sortBy` call. An explicit limit is required and must be positive.

## Continue with a cursor

`fetch()` returns a `Page<T>` with an opaque `nextCursor`:

```java
Page<User> first = users.query().sortBy("score", SortDirection.DESC).limit(20).fetch();

if (first.nextCursor() != null) {
  Page<User> next =
      users.query()
          .sortBy("score", SortDirection.DESC)
          .limit(20)
          .after(first.nextCursor())
          .fetch();
}
```

Keep the same filters and sort when using `after`. Do not decode, edit, or persist cursors as a
general-purpose query token. Adapters preserve a deterministic document-ID tie-breaker for equal
sort values.

## Deliberate limits

The public API does not provide joins, aggregation, arbitrary SQL, OR predicates, full-text search,
`LIKE`, `contains`, regular expressions, or unindexed-field filtering. If a field participates in a
query, declare it as part of the document metadata and let the selected adapter validate its type.
