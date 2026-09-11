# R2D1 JDBC

`r2d1-jdbc` adapts blocking JDBC databases to the asynchronous R2D1 `IndexStore` SPI. It stores
document identifiers and indexed field projections only. Authoritative serialized documents remain
in the configured `DocumentStore`.

The module keeps database-specific SQL and schema behavior behind an internal dialect boundary.
Applications use the same `JdbcIndexStore(DataSource, JdbcExecution)` API for every supported
database and provide the JDBC driver themselves.

## Supported databases

| Database | Status | Covered mode |
| --- | --- | --- |
| H2 | Built-in | Persistent embedded file database |
| HSQLDB | Built-in | Persistent embedded file database |

The dialect is selected from the exact JDBC product name. Unknown databases fail before schema
mutation.

## Dependencies

`r2d1-jdbc` exposes `r2d1-core` transitively. Applications do not normally need to declare
`r2d1-core` separately. A concrete `DocumentStore`, such as `r2d1-r2` or an application
implementation, is still required for complete document persistence.

The H2 and HSQLDB drivers are not bundled or exposed transitively. Applications must provide the
driver for the database selected by their `DataSource`.

### Gradle

Use a runtime dependency when configuration or a framework creates the `DataSource` without
importing H2 classes in application source code:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1-jdbc:<version>")
    runtimeOnly("com.h2database:h2:2.5.250")
}
```

If application source code imports an H2 class such as `org.h2.jdbcx.JdbcDataSource`, place H2 on
the compile classpath instead:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1-jdbc:<version>")
    implementation("com.h2database:h2:2.5.250")
}
```

For HSQLDB, use the same scope rule with the current supported driver version:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1-jdbc:<version>")
    runtimeOnly("org.hsqldb:hsqldb:2.7.4")
}
```

If application source code imports `org.hsqldb.jdbc.JDBCDataSource`, use
`implementation("org.hsqldb:hsqldb:2.7.4")` instead.

### Maven

Use runtime scope when the application does not import H2 classes:

```xml
<dependencies>
  <dependency>
    <groupId>dev.nexcraft</groupId>
    <artifactId>r2d1-jdbc</artifactId>
    <version>${r2d1.version}</version>
  </dependency>
  <dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.5.250</version>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

Omit `<scope>runtime</scope>` when application source code directly imports H2 classes. Maven then
uses its default compile scope.

The equivalent HSQLDB runtime dependency is:

```xml
<dependency>
  <groupId>org.hsqldb</groupId>
  <artifactId>hsqldb</artifactId>
  <version>2.7.4</version>
  <scope>runtime</scope>
</dependency>
```

Omit `<scope>runtime</scope>` when application source code directly imports HSQLDB classes.

## Configure H2

### Persistent file database

Use a distinct absolute file path for the index database:

```text
jdbc:h2:file:/absolute/path/to/r2d1-index
```

No close-delay option is required. H2 closes a persistent embedded database after its last
connection closes. A later connection to the same path reopens its tables, indexes, and rows.

External H2 server mode is not part of the current integration-test scope.

### Direct DataSource creation

When H2 is a compile dependency, an application can create its own `JdbcDataSource`:

```java
import org.h2.jdbcx.JdbcDataSource;

JdbcDataSource dataSource = new JdbcDataSource();
dataSource.setURL("jdbc:h2:file:/absolute/path/to/r2d1-index");
dataSource.setUser("sa");
dataSource.setPassword("");
```

Configuration frameworks and connection pools may create another `DataSource` implementation.
R2D1 requires only that connections returned by the source identify their database product as
`H2` through JDBC metadata.

## Configure HSQLDB

### Persistent file database

Use a distinct absolute file path for the index database:

```text
jdbc:hsqldb:file:/absolute/path/to/r2d1-index
```

HSQLDB reports the product name `HSQL Database Engine`, which the module detects automatically.
The integration-tested mode is the embedded persistent file mode. Server mode is not required by
`r2d1-jdbc` and is outside the current support boundary.

When reopening an existing file, `;ifexists=true` can be used to prevent an accidental new
database from being created:

```text
jdbc:hsqldb:file:/absolute/path/to/r2d1-index;ifexists=true
```

### Direct DataSource creation

When HSQLDB is a compile dependency, an application can create its own `JDBCDataSource`:

```java
import org.hsqldb.jdbc.JDBCDataSource;

JDBCDataSource dataSource = new JDBCDataSource();
dataSource.setUrl("jdbc:hsqldb:file:/absolute/path/to/r2d1-index");
dataSource.setUser("SA");
dataSource.setPassword("");
```

Configuration frameworks and connection pools may create another `DataSource` implementation. The
application owns that resource and the HSQLDB driver.

### HSQLDB lifecycle

`JdbcIndexStore` closes each operation connection, but it does not own the `DataSource` or shut
down the database. Before replacing or closing a lifecycle-bearing HSQLDB DataSource or pool,
stop admitting new operations, retain and await every in-flight `CompletionStage`, and close
`JdbcExecution`. The application must then execute `SHUTDOWN` through a short-lived connection and
only after that close the lifecycle-bearing DataSource or pool. `JdbcExecution.close()` does not
wait for submitted work. A later DataSource can reopen the same path.

## Connect a JDBC backend to R2D1

Create one bounded execution resource for blocking JDBC work and pass the selected database's
`DataSource` to `JdbcIndexStore`. The same code works for H2 and HSQLDB. Supply
`indexStore::initialize` as the collection initializer:

```java
import dev.nexcraft.r2d1.DocumentCodec;
import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.R2D1Collection;
import dev.nexcraft.r2d1.jdbc.JdbcExecution;
import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
import dev.nexcraft.r2d1.spi.DocumentStore;
import javax.sql.DataSource;

DataSource dataSource = applicationH2DataSource;
DocumentStore documentStore = applicationDocumentStore;
DocumentCodec documentCodec = applicationDocumentCodec;

try (JdbcExecution execution = JdbcExecution.create(4, 64)) {
  JdbcIndexStore indexStore = new JdbcIndexStore(dataSource, execution);

  R2D1 database =
      R2D1.builder()
          .collectionFactory(
              new PersistenceCollectionFactory(
                  documentStore, indexStore, documentCodec, indexStore::initialize))
          .build();

  R2D1Collection<User> users = database.collection(User.class);
  // Use the collection while the execution resource remains open.
}
```

`database.collection(User.class)` invokes the initializer and waits for schema initialization to
complete before returning the collection. When using `JdbcIndexStore` directly through the
asynchronous SPI, wait for or compose from `indexStore.initialize(User.class)` before issuing any
operation for that collection.

## Automatic database detection

No H2 flag, H2-specific constructor, or public dialect object is required. Initialization performs
the following sequence:

1. Inspect the document type and validate its collection and indexed fields.
2. Open a connection through the supplied `DataSource` on `JdbcExecution`.
3. Read `DatabaseMetaData.getDatabaseProductName()`.
4. Select the internal H2 dialect for exactly `H2`, or the HSQLDB dialect for exactly
   `HSQL Database Engine`.
5. Capture the active catalog and schema, then create or validate the collection schema.
6. Cache the initialized dialect for later operations on that collection.

Concurrent first initialization calls for the same collection share one initialization stage. A
failed initialization is cached and is not retried automatically by the same `JdbcIndexStore`
instance.

## Document metadata

Collection and indexed field names must match `[A-Za-z_][A-Za-z0-9_]*`. Names are validated and
quoted without arbitrary rewriting. A document must have exactly one non-blank `String` identifier.

```java
import dev.nexcraft.r2d1.annotation.Document;
import dev.nexcraft.r2d1.annotation.Id;
import dev.nexcraft.r2d1.annotation.Index;

@Document("users")
public record User(
    @Id String id,
    @Index String country,
    @Index long score,
    @Index double rating,
    @Index boolean active) {}
```

H2 maps indexed Java values as follows:

| Java field type | H2 column type | Notes |
| --- | --- | --- |
| `String` | `CHARACTER VARYING(1000000000)` | Non-null |
| `long` or `Long` | `BIGINT` | Non-null |
| `double` or `Double` | `DOUBLE PRECISION` | Non-null and finite |
| `boolean` or `Boolean` | `BOOLEAN` | Non-null |
| `Instant` | Not supported | Initialization fails before H2 schema mutation |

Every indexed value must be present and non-null when a document is written.

HSQLDB uses the same logical values and constraints:

| Java field type | HSQLDB column type | Notes |
| --- | --- | --- |
| `String` | `VARCHAR(1000000000)` | Non-null |
| `long` or `Long` | `BIGINT` | Non-null |
| `double` or `Double` | `DOUBLE PRECISION` | Non-null and finite |
| `boolean` or `Boolean` | `BOOLEAN` | Non-null |
| `Instant` | Not supported | Initialization fails before HSQLDB schema mutation |

H2 and HSQLDB use the same one-table-per-collection schema, complete replacement semantics, and
query operators. The dialect keeps the physical type declarations and UPSERT syntax database
specific.

## Schema behavior

Each collection maps to one table in the active database schema:

- `document_id` is the single `NOT NULL` primary-key column.
- Every `@Index` field is a required `NOT NULL` column.
- Every indexed field has a single-column index named `idx_<collection>_<field>`.
- Repeated initialization validates and reuses a compatible schema.
- A missing table, required columns on an empty table, and missing required indexes are created.
- A populated table that is missing a required column fails without changing its columns.
- Incompatible column types, nullability, primary keys, and required indexes fail initialization.
- Extra columns and indexes are preserved.
- Initialization never drops or renames objects, converts column types, or creates defaults for
  existing rows.

## Write, delete, and clear behavior

- Upsert uses the database dialect's `MERGE` keyed by `document_id`. A write replaces the complete
  indexed projection.
- Delete is idempotent. Deleting a missing document identifier succeeds.
- Clear deletes all rows from the collection table and preserves its schema and indexes.
- User identifiers and values are bound through prepared statements rather than interpolated into
  SQL.

The authoritative `DocumentStore` and the JDBC index are separate resources. R2D1 coordinates
their operations but does not provide a distributed transaction across them.

## Query behavior

- Multiple filters are combined with logical AND.
- `String`, integer, and double fields support `eq`, `notEq`, `gt`, `gte`, `lt`, and `lte`.
- Boolean fields support `eq` and `notEq` only.
- An explicit sort orders by the selected field and then `document_id` in the same direction.
- A query without an explicit sort orders by `document_id ASC`.
- Pagination uses keyset cursors and reads one additional row to determine whether another page
  exists.
- Query values and limits are prepared-statement parameters.

Continuation cursors contain the last document key and optional sort value, not a query fingerprint.
Pass a returned cursor to `after(...)` while retaining the same filters and sort:

```java
import dev.nexcraft.r2d1.Page;
import dev.nexcraft.r2d1.SortDirection;

Page<User> first =
    users.query().where("country").eq("NZ").sortBy("score", SortDirection.DESC).limit(20).fetch();

if (first.nextCursor() != null) {
  Page<User> second =
      users
          .query()
          .where("country")
          .eq("NZ")
          .sortBy("score", SortDirection.DESC)
          .limit(20)
          .after(first.nextCursor())
          .fetch();
}
```

## Resource ownership

`JdbcIndexStore` owns neither its `DataSource` nor its `JdbcExecution`:

- It obtains and closes one connection for each initialization or index operation.
- The application closes `JdbcExecution` when no further JDBC operations can occur.
- The application closes a lifecycle-bearing `DataSource` or connection pool that it creates.
- `JdbcExecution.create(maxConcurrency, maxPending)` owns bounded platform threads and shuts them
  down when closed.
- `JdbcExecution.using(executor, maxConcurrency, maxPending)` bounds admission but never shuts down
  the caller-owned executor.

Do not run collection operations after closing their execution resource.

## Failure mapping

JDBC failures are exposed through the storage exception hierarchy:

- Authentication and authorization failures map to `StorageException.Access`.
- Connection failures, database timeouts, transaction rollbacks, and HSQLDB file-lock failures
  map to
  `StorageException.Unavailable`.
- Other SQL and schema failures map to `StorageException.Operation`.

The original `SQLException` remains available as the cause. Public failure messages do not include
the JDBC URL, SQL text, credentials, or bound values.

## Current boundaries

The module does not provide a connection pool, retry policy, virtual-thread mode, framework
integration, or public dialect SPI. Applications own those choices outside R2D1. H2 and HSQLDB
persistent embedded file mode are the current integration-tested configurations. SQLite and other
databases are not supported.
