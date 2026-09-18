---
layout: ../../layouts/DocsLayout.astro
title: Micronaut 5
description: Configure the optional Micronaut 5 integration and its application-owned resources.
---

`r2d1-micronaut` conditionally creates the framework-neutral `R2D1` bean from Micronaut-managed
resources. It does not add Micronaut APIs to the base `r2d1` artifact.

## Dependency and runtime

```kotlin
implementation("dev.nexcraft:r2d1-micronaut:{{latestStableVersion}}")
```

Micronaut 5 and this integration currently require Java 25. The core and JDBC artifacts remain
compiled for Java 21. For a JDBC index, add `r2d1-jdbc` and the application-selected JDBC driver;
the Micronaut module does not add a pool or driver transitively.

## Enable the integration

The integration is disabled unless `r2d1.enabled=true`. It creates `R2D1` eagerly, so incomplete
configuration fails during application startup.

```yaml
r2d1:
  enabled: true
  index:
    type: jdbc
  backpressure:
    max-concurrency: 8
    max-pending: 32
  jdbc:
    datasource: default
    execution-mode: platform-thread
    backpressure:
      max-concurrency: 4
      max-pending: 16

datasources:
  default:
    url: jdbc:h2:file:./data/r2d1
```

The index backend is `jdbc` or `d1` when no application `IndexStore` bean exists. A single or
`@Primary` `DataSource` is selected when `r2d1.jdbc.datasource` is omitted; multiple candidates
require an exact bean name.

The example gives JDBC its own active and pending limits. Unset adapter fields inherit the matching
global field, then the library default of 8 active and 32 pending operations. Each R2, D1, or JDBC
adapter has an independent budget. The old flat JDBC keys `r2d1.jdbc.max-concurrency` and
`r2d1.jdbc.max-pending` remain explicit aliases; nested JDBC values take precedence over those
aliases, which take precedence over global values.

## Cloudflare configuration

The owned-client path uses the same adapter configuration as the framework-neutral API:

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
    backpressure:
      max-concurrency: 12
    client:
      max-concurrency: 16
  d1:
    account-id: ${CLOUDFLARE_ACCOUNT_ID}
    database-id: ${CLOUDFLARE_D1_DATABASE_ID}
    api-token: ${CLOUDFLARE_D1_API_TOKEN}
```

Secret-bearing configuration objects redact values from `toString()`. If the application supplies
a unique or `@Primary` `S3AsyncClient` or Java `HttpClient`, the integration borrows it and does not
close it. Otherwise it creates and closes the adapter resource it owns. An owned R2 client's
`client.max-concurrency` defaults to the effective R2 admission limit. An explicit lower value is
honored with a warning. The client override is not applied to a caller-owned S3 client; configure
that client directly.

## JDBC ownership and execution

`r2d1.jdbc.executor` can name an application-owned `Executor`. Micronaut wraps it with bounded
admission and never shuts it down. This adapter budget does not resize or close the executor or
change DataSource or pool capacity. When active and pending limits are full, submissions fail
immediately with `AdmissionRejectedException`. Do not configure `execution-mode` alongside an
external executor.
Without an external executor, the integration owns and closes `JdbcExecution`.

The integration never starts or closes a `DataSource`, connection pool, or database server. A remote
H2 URL and an embedded H2 URL use the same R2D1 wiring; the `DataSource` determines the endpoint.

## Overrides and native images

Application beans take precedence over integration-created resources in this order:

1. `R2D1`
2. `R2D1.CollectionFactory`
3. `DocumentStore`, `IndexStore`, and the collection initializer

Custom `DocumentCodec` instances are supplied per collection through the collection API.

The verified native smoke path is Micronaut `ApplicationContext` with the H2 JDBC backend. Document
types used in a native image require explicit reflection registration, for example with
`@ReflectiveAccess`. R2 and D1 native runtime execution is not currently a verified support path.

This module does not provide Micronaut Data repositories, HTTP endpoints, metrics, tracing,
Spring compatibility, R2DBC, retry policy, or database-server lifecycle management.

Admission limits cap simultaneous downstream operations; they are not a Cloudflare requests-per-
second limit or a Reactive Streams protocol. See [Configuration](/docs/configuration/) for queue,
cancellation, query fan-out, and rebuild behavior.
