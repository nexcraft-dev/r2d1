# R2D1 Quarkus extension

The optional extension integrates R2D1 with Quarkus 3.39.5 and Java 21+. It keeps the
core API, storage format and application serialization boundary unchanged. Managed
JDBC virtual-thread execution requires Java 25+, as required by the JDBC adapter.
The extension is opt-in and disabled by default. These new coordinates are release
artifacts; their addition here does not imply that they have already been published
or registered in the Quarkus extension catalog.

## Dependencies

Applications depend on the runtime artifact only. Quarkus resolves the matching
`r2d1-quarkus-deployment` artifact from its generated extension descriptor during
augmentation. Do not add the deployment artifact to an application runtime.

```kotlin
dependencies {
    implementation(platform("io.quarkus:quarkus-bom:3.39.5"))
    implementation("dev.nexcraft:r2d1-quarkus:1.7.0")
    // Choose these only for filesystem/JDBC backends:
    implementation("dev.nexcraft:r2d1-filesystem:1.7.0")
    implementation("dev.nexcraft:r2d1-jdbc:1.7.0")
    implementation("io.quarkus:quarkus-jdbc-h2")
    implementation("io.quarkus:quarkus-agroal")
}
```

The extension brings neither optional adapter, JDBC driver nor connection pool.
The application's Quarkus BOM must match the tested extension baseline. No other
Quarkus version compatibility matrix has been verified.

## Application beans

Provide an application CDI `DocumentCodec` implementing the core serialization
contract. There is no default JSON codec. Inject `R2D1` using constructor injection:

```java
@ApplicationScoped
public class Documents {
  private final R2D1 facade;

  public Documents(final R2D1 facade) {
    this.facade = facade;
  }
}
```

The default facade, collection factory, document store, index store and initializer
are `@DefaultBean` producers. A default-qualified application bean replaces the
matching producer. An application facade bypasses factory/storage assembly; an
application factory bypasses store/codec assembly. Custom stores bypass their
backend settings. A custom `IndexStore` must have an application
`PersistenceCollectionFactory.CollectionInitializer` for that exact store; the
extension never borrows an initializer from a different managed index.

Configuration is validated at runtime startup for enabled, selected wiring.
Unselected backend credentials are unnecessary. Resource resolution rejects
ambiguous default beans. Use CDI alternatives or exact resource names to resolve
ambiguity. Resources reached through dynamic lookup are retained by type.

## Filesystem and JDBC

```properties
quarkus.r2d1.enabled=true
quarkus.r2d1.document.type=filesystem
quarkus.r2d1.index.type=jdbc
quarkus.r2d1.filesystem.root-directory=/var/lib/my-app/documents
quarkus.r2d1.filesystem.executor=documents
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:documents;DB_CLOSE_DELAY=-1
quarkus.datasource.devservices.enabled=false
quarkus.r2d1.jdbc.max-concurrency=4
quarkus.r2d1.jdbc.max-pending=64
```

Supply a suitable blocking-I/O `Executor` CDI bean named `documents`; the application
owns its shutdown. An unnamed executor is selected using the default qualifier,
which may be ambiguous with Quarkus's own executor. Explicit naming is recommended.
The [smoke application](../r2d1-quarkus-integration-tests/src/main/java/dev/nexcraft/r2d1/quarkus/it/ApplicationResources.java)
shows a named, application-owned executor and its shutdown.

JDBC borrows the default `DataSource`, or `quarkus.r2d1.jdbc.datasource=<name>` selects
an exact CDI `@Named` bean or Quarkus `@io.quarkus.agroal.DataSource("<name>")` pool.
The application/Quarkus owns that datasource. Collection schema initialization is
lazy: it runs when a collection is requested, not during augmentation.

An application `JdbcExecution` takes priority. Otherwise the extension creates a
bounded platform-thread execution resource. Select `jdbc.execution-mode=virtual-thread`
explicitly on Java 25+. A named `jdbc.executor` creates a bounded wrapper over the
caller executor and cannot be combined with an explicit `jdbc.execution-mode`.
Concurrency must be positive and pending capacity nonnegative. Capacity exhaustion
fails the operation according to the JDBC adapter contract.

## Cloudflare R2 and D1

```properties
quarkus.r2d1.enabled=true
quarkus.r2d1.document.type=r2
quarkus.r2d1.index.type=d1
quarkus.r2d1.r2.endpoint=${R2_ENDPOINT}
quarkus.r2d1.r2.access-key-id=${R2_ACCESS_KEY_ID}
quarkus.r2d1.r2.secret-access-key=${R2_SECRET_ACCESS_KEY}
quarkus.r2d1.r2.bucket-name=${R2_BUCKET_NAME}
quarkus.r2d1.r2.region=auto
quarkus.r2d1.d1.account-id=${D1_ACCOUNT_ID}
quarkus.r2d1.d1.database-id=${D1_DATABASE_ID}
quarkus.r2d1.d1.api-token=${D1_API_TOKEN}
```

A default-qualified application `S3AsyncClient` is borrowed by the R2 store; only
the bucket setting is then required. Otherwise endpoint/access/secret create an
owned client. D1 requires account/database/token and optionally borrows an
application `HttpClient`. No cloud calls occur during augmentation or startup;
D1 collection initialization occurs when a collection is first requested.

The extension closes only its created stores, clients and JDBC execution/wrappers,
once, in reverse creation order. Startup failure closes partially created owned
resources. Borrowed clients, executors, execution resources and datasources retain
their application lifecycle.

## Configuration reference

All names below have the `quarkus.r2d1.` prefix. `enabled` is
`BUILD_AND_RUN_TIME_FIXED`; changing it requires rebuilding the application.
All other settings are `RUN_TIME`. There is no implicit backend choice.

| Property | Default | Used when |
|---|---|---|
| `enabled` | `false` | Activating the extension beans |
| `document.type` | None | No application document store (`r2`, `filesystem`) |
| `index.type` | None | No application index store (`d1`, `jdbc`) |
| `r2.endpoint`, `r2.access-key-id`, `r2.secret-access-key` | None | Creating an owned S3 client |
| `r2.bucket-name` | None | Creating an R2 store |
| `r2.region` | `auto` | Creating an owned S3 client |
| `d1.account-id`, `d1.database-id`, `d1.api-token` | None | Creating a D1 store |
| `filesystem.root-directory` | None | Creating a filesystem store |
| `filesystem.executor` | Default CDI executor | Selecting the borrowed executor |
| `jdbc.datasource` | Default CDI datasource | Selecting the borrowed datasource |
| `jdbc.executor` | None | Wrapping a named borrowed executor |
| `jdbc.execution-mode` | `platform-thread` | Creating owned JDBC execution |
| `jdbc.max-concurrency` | `4` | Creating owned execution/wrapper |
| `jdbc.max-pending` | `64` | Creating owned execution/wrapper |

The Gradle extension processor currently disables generated configuration
reference documentation when it cannot infer Maven coordinates. This explicit
reference and the configuration interfaces describe the supported settings.

## Verification and native boundary

The filesystem/H2 native smoke passed on macOS arm64 with GraalVM CE 25.0.2.
The smoke application supplies `software.amazon.awssdk.crt:aws-crt:0.48.4` because
AWS checksum signatures reference these optional classes during native linking.
This is an application dependency, not a forced extension dependency or evidence
of working native R2 transport.

The nonpublished `r2d1-quarkus-integration-tests` application exercises an explicit
codec and registered document type with filesystem/H2. Local adapter JARs model
external dependencies: extension code cannot link hot-reloadable workspace adapter
classes through the persistent extension classloader. Published consumers use
normal Maven coordinates and are independently tested with Gradle metadata and POMs.

```shell
./gradlew :r2d1-quarkus:check :r2d1-quarkus-deployment:check
./gradlew :r2d1-quarkus-integration-tests:test :r2d1-quarkus-integration-tests:quarkusIntTest
./gradlew :r2d1-quarkus-integration-tests:testNative -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
./gradlew verifyPublishedConsumer
```

Applications must explicitly register their document reflection metadata using
`@RegisterForReflection`, including nested types needed by their codec. The
extension does not scan/register every application class. Native evidence is
limited to the smoke application's filesystem/H2 configuration; R2/D1 native
transports have not been verified. See the native CI job for the supported smoke
command/toolchain and current result.

## Official implementation references

- [Writing extensions](https://quarkus.io/guides/writing-extensions/): runtime/deployment split and generated metadata.
- [Extension metadata](https://quarkus.io/guides/extension-metadata/): deployment artifact resolution.
- [CDI integration](https://quarkus.io/guides/cdi-integration/): additional beans and unused-bean retention.
- [CDI reference](https://quarkus.io/guides/cdi-reference/): application overrides through default beans.
- [Gradle tooling](https://quarkus.io/guides/gradle-tooling/): packaged and native integration tests.
- [Native application tips](https://quarkus.io/guides/writing-native-applications-tips/): reflection registration.
- [Class loading reference](https://quarkus.io/guides/class-loading-reference/): extension and reloadable application boundaries.
