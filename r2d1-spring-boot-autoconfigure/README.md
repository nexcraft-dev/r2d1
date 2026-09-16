# R2D1 Spring Boot auto-configuration

`r2d1-spring-boot-autoconfigure` is the auto-configuration module for the upcoming Spring Boot
4.0.6 integration. It is targeted for the `1.7.0` release and is not available from Maven Central
until the explicit `v1.7.0` release completes.

## Dependency

For a normal application, use the convenience starter and add the adapters used by the application:

```kotlin
dependencies {
    implementation("dev.nexcraft:r2d1-spring-boot-starter:1.7.0")
    implementation("dev.nexcraft:r2d1-filesystem:1.7.0") // only for filesystem documents
    implementation("dev.nexcraft:r2d1-jdbc:1.7.0") // only for JDBC indexes
}
```

The starter depends on this auto-configuration module and the standard Spring Boot starter. This
module depends on `r2d1`. Filesystem and JDBC adapters are compile-only integration points here;
they do not leak into the published POM or Gradle module metadata. JDBC drivers remain
application-provided. Use this auto-configuration artifact directly when composing a custom
starter.

The module targets Java 21. The managed JDBC `virtual-thread` mode requires Java 25 or newer and
fails explicitly on older runtimes; it never falls back to platform threads.

## Enable the integration

The auto-configuration is registered through Spring Boot's standard
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` mechanism. It
is disabled by default:

```yaml
r2d1:
  enabled: true
  document:
    type: r2
  index:
    type: d1
```

The document backend is `r2` or `filesystem`. The index backend is `d1` or `jdbc`. There are four
supported pairings: R2 + D1, R2 + JDBC, Filesystem + D1, and Filesystem + JDBC. All pairings use
the same framework-neutral `R2D1` and collection API.

## Configuration

R2 configuration is required when no application `S3AsyncClient` bean is available:

```yaml
r2d1:
  r2:
    endpoint: https://ACCOUNT_ID.r2.cloudflarestorage.com
    access-key-id: ${R2_ACCESS_KEY_ID}
    secret-access-key: ${R2_SECRET_ACCESS_KEY}
    bucket-name: documents
    region: auto
```

D1 configuration uses the core asynchronous REST adapter:

```yaml
r2d1:
  d1:
    account-id: ${CLOUDFLARE_ACCOUNT_ID}
    database-id: ${CLOUDFLARE_D1_DATABASE_ID}
    api-token: ${CLOUDFLARE_API_TOKEN}
```

Filesystem configuration selects a caller-owned executor by name or by unique/`@Primary` bean:

```yaml
r2d1:
  filesystem:
    root-directory: /var/lib/my-app/r2d1
    executor: filesystemExecutor
```

JDBC uses an application-owned `DataSource`. It also supports an optional caller-owned executor,
or an R2D1-managed bounded execution resource:

```yaml
r2d1:
  jdbc:
    datasource: applicationDataSource
    executor: jdbcExecutor
    backpressure:
      max-concurrency: 4
      max-pending: 64
```

Each integration-created R2, D1, JDBC, and Filesystem adapter has an independent admission budget. The default is 8
active and 32 pending operations. Set `r2d1.backpressure.max-concurrency` and
`r2d1.backpressure.max-pending` for global fallbacks, or use the matching adapter's nested
`backpressure` properties. Unset adapter fields inherit independently. The legacy flat JDBC keys
`r2d1.jdbc.max-concurrency` and `r2d1.jdbc.max-pending` remain explicit aliases; nested JDBC values
take precedence over those aliases, which take precedence over the global values.

For an integration-owned R2 client, `r2d1.r2.client.max-concurrency` overrides the default derived
from the effective R2 admission limit. A lower explicit value is honored with a warning. This
setting does not modify an application-owned `S3AsyncClient`. When the active and pending budget is
full, further operations fail with `AdmissionRejectedException`; this is a concurrency limit, not
a Cloudflare requests-per-second limit. See the
[configuration guide](https://r2d1.nexcraft.dev/docs/configuration/) for cancellation and query or
rebuild fan-out behavior.

When `executor` is absent, the default execution mode is `platform-thread`. Set
`execution-mode: virtual-thread` explicitly on Java 25 or newer when the application wants the
R2D1-managed virtual-thread executor. An execution mode cannot be combined with a configured
caller-owned executor because R2D1 does not inspect or redefine that executor's thread model.

`datasource` and the filesystem/JDBC executor name are exact Spring bean names. Without a name,
R2D1 accepts one unique bean or a resolvable `@Primary` bean and fails startup when the dependency
is missing or ambiguous.

## Application bean precedence

Application beans take precedence through `@ConditionalOnMissingBean`. Define any of these beans to
replace the corresponding R2D1 default:

- `R2D1`
- `R2D1.CollectionFactory`
- `DocumentStore`
- `IndexStore`
- `DocumentCodec`
- `PersistenceCollectionFactory.CollectionInitializer`
- `JdbcExecution`

If an application supplies a `CollectionFactory` or `R2D1`, the integration does not require the
other persistence components it would otherwise assemble.

## Resource ownership

The integration reuses an application-provided `S3AsyncClient`, Java `HttpClient`, `DataSource`, or
`Executor`; it does not close those resources. When no R2 client or D1 HTTP client is supplied, the
core adapter creates and owns its client and Spring closes the adapter with the context. When no
`JdbcExecution` is supplied, the integration owns and closes the managed execution resource.

The integration never creates or manages a connection pool, JDBC driver, database server, or
application executor. `JdbcIndexStore` owns neither the `DataSource` nor the execution resource.

## Scope and native images

This module provides conventional Spring Boot auto-configuration only. It does not add Spring
Data repositories, transaction integration, AOP, controllers, retry or compensation policies,
R2DBC support, or database-server lifecycle management. No Spring native hint is included: the
optional classpath boundary and Boot AOT compatibility should be reviewed again when a concrete
native-image requirement exists.
