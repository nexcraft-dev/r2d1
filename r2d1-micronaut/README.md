# R2D1 Micronaut 5 Integration

`r2d1-micronaut` integrates the framework-neutral R2D1 API with Micronaut 5. It conditionally
creates the top-level `R2D1` bean, selects application-owned resources, and manages only resources
that the integration creates itself. It does not add Micronaut APIs to `r2d1`.

## Requirements and dependencies

Micronaut 5 and this module require Java 25. The `r2d1` and `r2d1-jdbc` artifacts remain compiled
for Java 21. The module uses Micronaut Platform 5.1.5 and is built with the Micronaut library Gradle
plugin 5.0.2.

Add the integration. It transitively provides `r2d1`, including the Cloudflare R2 and D1 adapters:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1-micronaut:<version>")
}
```

For a JDBC index, add `r2d1-jdbc` and the application-selected JDBC driver. The Micronaut
integration does not transitively add the optional JDBC adapter, a connection pool, or a database
driver.

## Configuration

The integration is disabled unless `r2d1.enabled=true`. Enabling it creates `R2D1` eagerly, so an
invalid or incomplete configuration fails during application startup.

| Property | Default | Required when | Description |
|---|---:|---|---|
| `r2d1.enabled` | `false` | Always | Enables conditional R2D1 bean creation. |
| `r2d1.index.type` | none | No application `IndexStore` bean exists | Index backend: `jdbc` or `d1`. |
| `r2d1.jdbc.datasource` | single or primary bean | JDBC backend has multiple `DataSource` beans | Exact `DataSource` bean name. |
| `r2d1.jdbc.executor` | none | Never | Exact caller-owned `Executor` bean name. |
| `r2d1.jdbc.execution-mode` | `platform-thread` | Never | Managed mode: `platform-thread` or `virtual-thread`. |
| `r2d1.backpressure.max-concurrency` | `8` | Never | Global fallback active-operation limit. |
| `r2d1.backpressure.max-pending` | `32` | Never | Global fallback pending-operation limit. |
| `r2d1.jdbc.backpressure.max-concurrency` | inherited | Never | JDBC active-operation override. |
| `r2d1.jdbc.backpressure.max-pending` | inherited | Never | JDBC pending-operation override. |
| `r2d1.jdbc.max-concurrency` | no override | Never | Legacy flat JDBC active limit; honored when explicitly configured. |
| `r2d1.jdbc.max-pending` | no override | Never | Legacy flat JDBC pending limit; honored when explicitly configured. |
| `r2d1.r2.endpoint` | none | R2 creates its S3 client | Cloudflare R2 S3 endpoint URI. |
| `r2d1.r2.access-key-id` | none | R2 creates its S3 client | R2 access key ID. |
| `r2d1.r2.secret-access-key` | none | R2 creates its S3 client | R2 secret access key. |
| `r2d1.r2.bucket-name` | none | R2 creates or borrows a client | R2 bucket name. |
| `r2d1.r2.region` | `auto` | R2 creates its S3 client | S3 signing region. |
| `r2d1.r2.client.max-concurrency` | effective R2 limit | R2 creates its S3 client | Low-level S3/Netty client override. |
| `r2d1.d1.account-id` | none | D1 backend | Cloudflare account ID. |
| `r2d1.d1.database-id` | none | D1 backend | Cloudflare D1 database ID. |
| `r2d1.d1.api-token` | none | D1 backend | Cloudflare API token. |

Secret-bearing configuration objects redact endpoint, credential, bucket, account, database, and
token values from `toString()` output. Generated Micronaut and Spring-compatible configuration
metadata describe the supported property names.

Integration-created R2, D1, and JDBC adapters each receive an independent admission budget. Per-adapter backpressure fields
override the matching global fields, which otherwise use 8 active and 32 pending operations. The
legacy flat JDBC keys remain supported: nested JDBC values take precedence, then explicitly supplied
flat values, then global values. The default owned R2 client uses the effective R2 active limit;
`r2d1.r2.client.max-concurrency` overrides it. A lower explicit value is honored with a warning.
This client setting is not applied to a caller-owned S3 client. See the
[configuration guide](https://r2d1.nexcraft.dev/docs/configuration/) for rejection, cancellation,
query fan-out, and rebuild behavior.

## JDBC

A single or `@Primary` `DataSource` is selected when `r2d1.jdbc.datasource` is omitted. If several
unqualified candidates remain, startup fails and asks for an exact bean name. Micronaut SQL commonly
names multiple `DataSource` beans after their `datasources.<name>` configuration key.

Embedded H2 configuration and a remote H2 URL use the same R2D1 wiring:

```yaml
r2d1:
  enabled: true
  index:
    type: jdbc
  jdbc:
    datasource: default
    execution-mode: platform-thread
    backpressure:
      max-concurrency: 4
      max-pending: 64

datasources:
  default:
    url: jdbc:h2:file:./data/r2d1
    # A remote deployment changes only this DataSource URL:
    # url: jdbc:h2:tcp://database.example:9092/./r2d1
```

When `r2d1.jdbc.executor` names an application `Executor`, R2D1 wraps it with bounded admission and
never shuts the executor down. Do not also set `execution-mode`; the external executor defines its
thread model. Without an external executor, the integration owns and closes `JdbcExecution`.
`virtual-thread` is an explicit Java 25 opt-in. The integration never starts or closes a
`DataSource`, connection pool, or database server.

## Cloudflare R2 and D1

The standard owned-client configuration is:

```yaml
r2d1:
  enabled: true
  index:
    type: d1
  r2:
    endpoint: https://<account-id>.r2.cloudflarestorage.com
    access-key-id: ${R2_ACCESS_KEY_ID}
    secret-access-key: ${R2_SECRET_ACCESS_KEY}
    bucket-name: documents
    region: auto
  d1:
    account-id: ${CLOUDFLARE_ACCOUNT_ID}
    database-id: ${CLOUDFLARE_D1_DATABASE_ID}
    api-token: ${CLOUDFLARE_D1_API_TOKEN}
```

If the application supplies a unique or `@Primary` `S3AsyncClient` or Java `HttpClient`, the
integration borrows it and does not close it. Otherwise, it creates the existing adapter client and
closes the store at context shutdown. D1 continues to use Java's HTTP transport; this module does
not substitute Micronaut HTTP Client. Ambiguous client beans and partial credentials fail at
startup without performing network I/O.

## Overrides and ownership

The following application beans take precedence, from highest to lowest:

1. `R2D1`
2. `R2D1.CollectionFactory`
3. `DocumentStore`, `IndexStore`, `DocumentCodec`, and
   `PersistenceCollectionFactory.CollectionInitializer`

An application `R2D1` prevents integration-owned storage resources from being instantiated. A
custom `IndexStore` must have a matching `PersistenceCollectionFactory.CollectionInitializer`;
startup otherwise reports how to provide one. Multiple beans at an override level require a
resolvable `@Primary` bean.

## GraalVM native image

The supported native smoke path is Micronaut `ApplicationContext` with the H2 JDBC backend. The base
and JDBC artifacts inspect document fields through reflection, so each document type used in a native image must
be explicitly registered, for example:

```java
@Document("users")
@ReflectiveAccess
record User(@Id String id, @Index String country, String name) {}
```

This annotation is an application responsibility. The library does not install broad production
reflection configuration. R2 and D1 conditional wiring is tested on the JVM; their native runtime
execution is not currently a verified support path.

Run the focused native test with GraalVM 25:

```shell
./gradlew -Pr2d1.javaToolchainVersion=25 :r2d1-micronaut:nativeTest
```

## Scope

This module does not provide Micronaut Data repositories, transaction integration, repository
proxies, HTTP endpoints, management endpoints, metrics, tracing, Spring compatibility, R2DBC,
retry policy, automatic thread-mode selection, or database-server lifecycle management.
