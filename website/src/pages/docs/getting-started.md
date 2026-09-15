---
layout: ../../layouts/DocsLayout.astro
title: Getting Started
description: Choose document and index backends, configure a framework-neutral R2D1 collection, and run the first indexed query.
---

R2D1 setup has two independent backend choices followed by one common API path. Choose where
complete documents live, choose where indexed fields live, and then configure the collection
facade with those two stores.

## 1. Choose a document store

The `DocumentStore` is authoritative for complete serialized documents, reads by identifier, and
logical existence. Choose one:

- [R2 DocumentStore](/docs/document-stores/r2/) stores documents in Cloudflare R2 through an AWS
  SDK asynchronous client.
- [Filesystem DocumentStore](/docs/document-stores/filesystem/) stores canonical documents in a
  local filesystem with atomic replacement.

## 2. Choose an index store

The `IndexStore` is a rebuildable projection of document identifiers and explicitly indexed fields.
Choose one:

- [D1 IndexStore](/docs/index-stores/d1/) uses Cloudflare D1 through its REST API and Java's
  asynchronous `HttpClient`.
- [JDBC IndexStore](/docs/index-stores/jdbc/) supports H2, HSQLDB, and local SQLite through an
  application-owned `DataSource`.

These choices create four supported pairings—R2 + D1, R2 + JDBC, Filesystem + D1, or Filesystem +
JDBC—but they do not create four different collection APIs.

## 3. Add the required modules

The core artifact supplies the common API and Cloudflare R2/D1 adapters:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1:<version>")
}
```

Add only the integration modules used by the application:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1-filesystem:<version>")
    implementation("dev.nexcraft:r2d1-jdbc:<version>")
    implementation("dev.nexcraft:r2d1-micronaut:<version>")
}
```

JDBC drivers are application-provided and are not bundled by `r2d1-jdbc`. See [Configuration](/docs/configuration/)
for the complete module map and ownership rules.

## 4. Define a document

Annotate one non-blank `String` member as the identifier and mark fields that the index must store.
Only indexed fields can participate in filtering and sorting.

```java
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;

@Document("users")
public record User(
    @Id String id,
    @Index String country,
    @Index long score,
    String name) {}
```

R2D1 does not select a JSON library. Supply a `DocumentCodec` that serializes the domain object to
`StoredDocument` and deserializes it again when the collection hydrates a result.

## 5. Configure the common collection API

The following example leaves backend construction to the application so the two storage roles stay
visible. `documentStore`, `indexStore`, and `documentCodec` are application-owned values.

```java
import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;

DocumentStore documentStore = applicationDocumentStore;
IndexStore indexStore = applicationIndexStore;
DocumentCodec documentCodec = applicationDocumentCodec;

R2D1 database =
    R2D1.builder()
        .collectionFactory(
            new PersistenceCollectionFactory(
                documentStore, indexStore, documentCodec, indexStore::initialize))
        .build();

R2D1Collection<User> users = database.collection(User.class);
```

Opening the collection initializes or validates the index metadata before returning the collection.
The factory does not take ownership of the stores, codec, or caller-owned execution resources.

## 6. Put, get, and delete

```java
User user = new User("user-123", "NZ", 42, "Ada");
users.put(user);

Optional<User> stored = users.get("user-123");

users.delete("user-123");
Optional<User> absent = users.get("user-123");
```

`put` creates or replaces the document using the `@Id` value. `get` reads authoritative document
storage, and deleting an absent identifier has no effect.

## 7. Run an indexed query

```java
import dev.nexcraft.r2d1.Page;
import dev.nexcraft.r2d1.SortDirection;

Page<User> first =
    users.query()
        .where("country")
        .eq("NZ")
        .sortBy("score", SortDirection.DESC)
        .limit(20)
        .fetch();

if (first.nextCursor() != null) {
  Page<User> second =
      users.query()
          .where("country")
          .eq("NZ")
          .sortBy("score", SortDirection.DESC)
          .limit(20)
          .after(first.nextCursor())
          .fetch();
}
```

The cursor is opaque. Keep the same filters and sort when requesting the next page. See
[Querying](/docs/querying/) for the supported comparison operators and ordering rules. Read
[Consistency and Recovery](/docs/consistency/) before operating both stores in production.
