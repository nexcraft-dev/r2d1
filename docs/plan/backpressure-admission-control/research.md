# Research — R2D1 Backpressure and Admission Control

## Request

Add bounded, non-blocking admission control for each downstream R2, D1, JDBC, and filesystem I/O operation. Preserve the asynchronous `CompletionStage` storage contracts and persistence behavior, expose global and adapter-specific settings with defaults of 8 concurrent operations and 32 pending operations, derive the R2D1-owned S3 client capacity from R2 admission, and integrate the settings with Spring Boot and Micronaut. Keep Failsafe behind an R2D1-owned abstraction, distinguish local rejection from storage failure, test permit lifecycle and fan-out, and update the English documentation. Do not commit, push, create a PR or tag, or release; report the final diff summary and worktree state.

## Research Mode

- Source: MAIN
- Additional model calls: One bounded CLI Worker research request was attempted after the deterministic router selected `CLI_WORKER`; the provider failed to initialize with `Operation not permitted` before returning evidence. No retry was made. Main completed the bounded repository inspection; the remaining routed batches were not submitted because the shared Worker runner was unavailable.

## Repository Snapshot

- Repository root: `/Users/sean/Documents/projects/r2d1`
- Current branch: `feat/backpressure-admission-control`
- Worktree state: Source tree was clean before orchestration artifacts were created. The current untracked paths are the intended artifacts under `docs/plan/backpressure-admission-control/`; no pre-existing changes were present. The feature branch is based on `release/1.7.0` at `805fed6dbf1d977b931c6750191276577478923d`.
- Relevant build roots and modules:
  - `r2d1` — Core public API, R2 and D1 adapters, persistence orchestration, and shared tests.
  - `r2d1-jdbc` — JDBC `IndexStore` and bounded JDBC execution resource.
  - `r2d1-filesystem` — Filesystem `DocumentStore` dispatching blocking I/O to a supplied executor.
  - `r2d1-spring-boot-autoconfigure` — Spring Boot 4 properties and adapter factories.
  - `r2d1-spring-boot-starter` — Starter packaging and integration verification.
  - `r2d1-micronaut` — Standalone Micronaut 5 configuration and factories; Java 25 toolchain.
  - `r2d1-integration-tests` — Opt-in live Cloudflare R2/D1 integration suite.

## Scope Map

| Area | Entry point or owner | Why it matters | Evidence |
|---|---|---|---|
| Public storage contracts | `r2d1/src/main/java/dev/nexcraft/r2d1/spi/DocumentStore.java`, `IndexStore.java` | Both expose asynchronous operations returning `CompletionStage`; admission must remain per low-level call. | `DocumentStore`, `IndexStore` |
| Public synchronous boundary | `r2d1/src/main/java/dev/nexcraft/r2d1/internal/persistence/StageSupport.java` | `await()` is the only production blocking boundary; failure unwrapping determines synchronous rejection behavior. | `StageSupport.await`, `StageSupport.mapFailure` |
| Persistence fan-out and rebuild | `r2d1/src/main/java/dev/nexcraft/r2d1/internal/persistence/PersistentR2D1Collection.java` | Query and rebuild fetch documents by starting one R2 `get` per returned ID/key; rebuild uses pages of 100 and prepares the first page before clearing D1. | `fetchDocuments`, `prepareRebuildPage`, `REBUILD_PAGE_SIZE` |
| R2 adapter | `r2d1/src/main/java/dev/nexcraft/r2d1/r2/R2DocumentStore.java`, `R2ClientFactory.java` | Each S3 request returns a stage. The configured constructor owns its client; the caller-client constructor borrows it. Client failures are translated in `executeValue`/`executeVoid`. | `R2DocumentStore.put/get/delete/list`, `R2ClientFactory.create` |
| D1 adapter | `r2d1/src/main/java/dev/nexcraft/r2d1/d1/D1IndexStore.java`, `d1/internal/transport/D1Transport.java` | All queries, including schema initialization, cross the same transport boundary; the REST implementation uses `HttpClient.sendAsync`. | `D1IndexStore` constructor, `D1SchemaManager`, `RestD1Transport.execute` |
| JDBC adapter | `r2d1-jdbc/src/main/java/dev/nexcraft/r2d1/jdbc/JdbcExecution.java` | Already provides a bounded pending queue and dispatches one blocking JDBC operation at a time through its executor. It owns only internally created executors, not a supplied executor or `DataSource`. | `JdbcExecution.execute`, `close`, `JdbcExecutionConfig`, `JdbcIndexStore` |
| Filesystem adapter | `r2d1-filesystem/src/main/java/dev/nexcraft/r2d1/filesystem/FileSystemDocumentStore.java` | Filesystem calls are blocking work submitted to a supplied executor; bounding admission prevents excess executor submissions without resizing or closing that executor. | `FileSystemDocumentStore.submit` |
| Spring configuration | `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring` | Global and adapter properties feed separate Core, JDBC, and Filesystem factories; existing JDBC flat properties have defaults that mask inheritance unless explicitness is resolved. | `R2D1Properties`, `R2D1R2Properties`, `R2D1D1Properties`, `R2D1JdbcProperties`, `R2D1FilesystemProperties`, `internal/*Configuration.java` |
| Micronaut configuration | `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut` | Root, R2, D1, and JDBC configuration records feed conditional factories; JDBC currently defaults to 4/64 and must participate in new inheritance without mixing execution mode with admission. | `R2D1Configuration`, `R2D1R2Configuration`, `R2D1D1Configuration`, `R2D1JdbcConfiguration`, `internal/*Factory.java` |
| User documentation | `website/src/pages/docs/configuration.md` and adapter pages | Explain zero-config defaults, hierarchy, AWS client override, local rejection, rate-limit distinction, fan-out, and rebuild behavior. | `website/src/pages/docs/document-stores/{r2,filesystem}.md`, `index-stores/{d1,jdbc}.md`, `micronaut.md`, `README.md` |

## Execution and Data Flow

1. Public synchronous collection operations enter `PersistentR2D1Collection`; `StageSupport.await()` waits only at the outer API boundary and unwraps `CompletionStage` failures.
2. PUT performs one R2 `DocumentStore.put`, then one D1 `IndexStore.upsert`; the permit for each must be released when that adapter's own stage is terminal, not when the larger PUT pipeline ends.
3. Query performs one D1 `IndexStore.query`; after the index stage completes, `fetchDocuments()` invokes one R2 `DocumentStore.get` for each ordered ID. R2 admission must independently cap each GET while the D1 permit is already released.
4. Rebuild lists R2 keys by a fixed page size of 100, fetches that page's documents, clears D1 only after the first page has been prepared, then writes the page's index entries sequentially. It does not buffer the full collection.
5. R2 translates downstream SDK failures inside `R2DocumentStore`; a local rejection must occur before this mapper so it remains distinguishable. The caller-owned `S3AsyncClient` constructor does not close or reconfigure that client.
6. D1 schema and data statements both use the `D1Transport` passed to `D1SchemaManager`; an admission wrapper at this boundary covers initialization as well as CRUD/query calls. The caller-owned `HttpClient` stays borrowed.
7. JDBC calls enter `JdbcExecution.execute`, which currently admits up to `maxConcurrency`, stores up to `maxPending`, and dispatches accepted work via platform, virtual, or caller-supplied executor. Mode selects execution resources; it is independent from capacity.
8. Filesystem operations submit blocking work to the caller-supplied executor. An admission gate can bound accepted submissions while preserving executor ownership.

## Public Contracts and Compatibility

| Contract | Current behavior | Constraint | Evidence |
|---|---|---|---|
| `DocumentStore` / `IndexStore` | Asynchronous `CompletionStage` methods; no executor choice in the SPI. | Keep all adapter operations asynchronous and acquire one permit per downstream I/O. | `spi/DocumentStore.java`, `spi/IndexStore.java` |
| Blocking and failure propagation | `StageSupport.await()` is the production blocking boundary and unwraps completion wrappers. | Do not add `.join()`/`.get()` to admission or persistence paths; test `AdmissionRejectedException` at the synchronous boundary. | `internal/persistence/StageSupport.java` |
| Persistence semantics | R2 is authoritative and D1 is rebuildable; a D1 failure after an R2 write/delete can surface as `PersistenceException.PartialFailure`. Query order is driven by D1 and retained while R2 documents are fetched. | Preserve storage ordering and partial-failure meaning; when rejection follows an already successful authoritative write/delete, preserve the partial-failure wrapper with the admission rejection as its cause. | `internal/persistence/PersistentR2D1Collection.java`, `spi/PersistenceException.java` |
| Storage errors | `StorageException` represents access, unavailable-storage, and operation failures. | Capacity rejection is local admission state, not a storage outage; do not map it into `StorageException`. Executor/client failures retain their existing mappings. | `spi/StorageException.java`, `R2DocumentStore`, `D1IndexStore`, `JdbcExecution`, `FileSystemDocumentStore` |
| Owned and borrowed clients | R2 and D1 constructors either own an internally created client or borrow a caller-supplied one. JDBC/Filesystem borrow caller `DataSource`/executor resources. | Gate operations without mutating, resizing, closing, or introspecting caller-owned resources. | `R2DocumentStore`, `D1IndexStore`, `JdbcExecution`, `FileSystemDocumentStore` |
| `R2Config` | Public five-component record with existing four- and five-argument constructors. | Avoid changing the record components; add an overload or separate options if direct users need a low-level owned-client override. | `r2/R2Config.java` |
| JDBC thread model | `PLATFORM_THREAD` is the default, `VIRTUAL_THREAD` is explicit, and a supplied executor remains caller-owned. | Do not add `AUTO` or couple mode to `maxConcurrency`/`maxPending`; capacity defaults and inherited configuration remain a separate concern. | `jdbc/JdbcExecutionMode.java`, `JdbcExecutionConfig.java`, integration properties/configuration |
| Framework configuration | Spring and Micronaut expose public record properties; JDBC has legacy flat `max-concurrency`/`max-pending` values defaulting to 4/64. | Preserve existing property names and record constructors where possible. Treat explicitly supplied legacy JDBC values as adapter overrides, while allowing global values to inherit when legacy values are absent. New values use adapter `.backpressure.*` paths. | `R2D1JdbcProperties.java`, `R2D1JdbcConfiguration.java`, framework contract tests |
| Index/query surface | `Query.limit` is caller-defined; the existing `@Index` maximum is five. | Do not change query or index limits. With configured `maxConcurrency + maxPending` admission capacity, a single fan-out larger than that capacity can now fail with local rejection. | `Query.java`, annotation contract tests, `fetchDocuments` |
| Rebuild | Page size is 100; first page is prepared before D1 clear. | R2 page fan-out larger than admission capacity can reject; preserve first-page-before-clear ordering and record this new overload behavior. | `PersistentR2D1Collection.prepareRebuildPage` |

## Build and Test Evidence

### Unit scope
- Status: REQUIRED
- Working directory: `/Users/sean/Documents/projects/r2d1`
- Command: `./gradlew check`
- Expected JUnit XML: `<module>/build/test-results/{test,optionalAdapterTest}/TEST-*.xml`
- Expected HTML report: `<module>/build/reports/tests/{test,optionalAdapterTest}/index.html`
- Discovery evidence: `.github/workflows/ci.yml` runs `./gradlew build` and `./gradlew check`; root `build.gradle.kts` configures JUnit Platform and JaCoCo; `r2d1-micronaut/build.gradle.kts` wires `optionalAdapterTest` into `check`.

### Component or integration scope
- Status: REQUIRED
- Working directory: `/Users/sean/Documents/projects/r2d1`
- Command: `./gradlew :r2d1-integration-tests:integrationTest`
- Expected JUnit XML: `r2d1-integration-tests/build/test-results/integrationTest/TEST-*.xml`
- Expected HTML report: `r2d1-integration-tests/build/reports/tests/integrationTest/index.html`
- Discovery evidence: `r2d1-integration-tests/build.gradle.kts` defines an opt-in live Cloudflare `integrationTest`; it requires dedicated R2/D1 credentials and `R2D1_IT_CONFIRM_DEDICATED_RESOURCES=true`. `README.md` documents the command. Do not report it as passed if those dedicated resources are unavailable.

Additional focused commands found in repository conventions:

- Core/R2/D1: `./gradlew :r2d1:test`
- JDBC and Filesystem: `./gradlew :r2d1-jdbc:test :r2d1-filesystem:test`
- Spring: `./gradlew :r2d1-spring-boot-autoconfigure:test :r2d1-spring-boot-starter:test`
- Micronaut: `./gradlew -Pr2d1.javaToolchainVersion=25 :r2d1-micronaut:check`; `check` includes `optionalAdapterTest`.
- Java 25 CI adapter verification: `./gradlew -Pr2d1.javaToolchainVersion=25 :r2d1-jdbc:check :r2d1-micronaut:check :r2d1-spring-boot-autoconfigure:check :r2d1-spring-boot-starter:check` from `.github/workflows/ci.yml`.
- Website documentation type check: from `website`, `npm run check`, as defined in `.github/workflows/website.yml`.

## Candidate Impact Areas

| Area | Likely implementation, contract, reference, or test files and symbols | Evidence-backed reason |
|---|---|---|
| Core policy/API | `r2d1/build.gradle.kts`; new `BackpressureConfig`, `AdmissionController`, `AdmissionRejectedException`; internal provider/controller; `src/main/resources/META-INF/services`; new Core tests | Owns the framework-neutral limits, stage lifecycle, local rejection contract, and Failsafe isolation boundary. |
| R2/D1 boundaries | `r2d1/src/main/java/dev/nexcraft/r2d1/r2/{R2DocumentStore,R2ClientFactory}.java`; `d1/{D1IndexStore.java,internal/transport/RestD1Transport.java}`; R2/D1 tests | Every low-level SDK or HTTP stage must acquire/release independently; R2 client capacity belongs to the R2D1-owned client only. |
| JDBC and Filesystem | `r2d1-jdbc/.../JdbcExecution.java`; `r2d1-filesystem/.../FileSystemDocumentStore.java`; adapter tests | JDBC already has a separate bounded queue; Filesystem currently delegates admission to the supplied executor. Both need the same rejection and completion rules without changing thread/resource ownership. |
| Spring | `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/{R2D1Properties,R2D1R2Properties,R2D1D1Properties,R2D1JdbcProperties,R2D1FilesystemProperties}.java`; `spring/internal/*Configuration.java`; `SpringAutoConfigurationTest.java` | Bind global and adapter overrides, preserve old JDBC flat properties, create independent per-adapter budgets, derive/warn for the owned S3 client. |
| Micronaut | `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/{R2D1Configuration,R2D1R2Configuration,R2D1D1Configuration,R2D1JdbcConfiguration}.java`; `internal/{R2Factory,D1Factory,JdbcFactory}.java`; `ConfigurationContractTest.java` | Mirror the inheritance and client derivation in Micronaut factories while preserving optional adapter and Java 25 constraints. |
| Persistence behavior tests | `r2d1/src/test/java/dev/nexcraft/r2d1/ConsistencyRecoveryTest.java`; query/fetch tests and controlled R2/D1 fakes | Prove query fan-out observes independent adapter limits and rebuild does not bypass R2 admission or clear D1 before its first page is ready. |
| Docs | `website/src/pages/docs/configuration.md`, R2/D1/JDBC/Filesystem pages, `micronaut.md`, `README.md` | Document defaults/inheritance, bounded rejection, D1 concurrency versus Cloudflare rate limits, caller-owned resource behavior, AWS client override, and the non-reactive meaning of “backpressure.” |

## Uncertainties and User Decisions

- Resolved by evidence:
  - Use 8/32 as R2D1 policy defaults, not Cloudflare guarantees; the attachment explicitly selects these candidates.
  - Apply admission to Filesystem operations: each blocking operation is submitted to a caller-supplied executor, so a bounded gate limits R2D1-submitted downstream work without taking ownership of that executor.
  - Treat Spring/Micronaut adapter budgets as independent instances inheriting values; do not implement a shared global budget.
  - Keep Cloudflare request-per-window rate limiting out of scope; concurrency admission does not guarantee API quota compliance.
  - Keep the existing 100-key rebuild page and current ordered query semantics. New overload rejection may fail a page/query whose fan-out exceeds that adapter's configured `maxConcurrency + maxPending`; the first rebuild page remains prepared before D1 clear.
  - Explicit user instruction forbids commit/push/PR/tag/release, overriding the orchestrator's default commit finalization.
- Requires user decision: none identified beyond the orchestrator's plan approval gate.
- Remaining uncertainty:
  - The project AWS SDK BOM is 2.54.13 while the current official Netty builder reference reviewed is 2.54.17. Verify the exact 2.54.13 API and accepted values before deriving any `maxPendingConnectionAcquires` behavior. The framework-visible low-level property should use AWS's actual `maxConcurrency` name; do not rename it to “connections.”
  - The request's mandatory CLI Worker Walkthrough may encounter the same provider initialization restriction as Research. If so, follow the orchestrator's Walkthrough failure gate and obtain direction rather than fabricating Worker evidence.

## Evidence Index

- `r2d1/src/main/java/dev/nexcraft/r2d1/internal/persistence/StageSupport.java` — sole blocking boundary and completion-wrapper unwrapping.
- `r2d1/src/main/java/dev/nexcraft/r2d1/internal/persistence/PersistentR2D1Collection.java` — R2-first persistence ordering, query/rebuild fan-out, ordered result mapping, and page-before-clear rebuild behavior.
- `r2d1/src/main/java/dev/nexcraft/r2d1/r2/R2DocumentStore.java` — S3 stage mapping and owned/borrowed client behavior.
- `r2d1/src/main/java/dev/nexcraft/r2d1/d1/D1IndexStore.java` and `d1/internal/transport/RestD1Transport.java` — shared D1 transport and asynchronous REST boundary.
- `r2d1-jdbc/src/main/java/dev/nexcraft/r2d1/jdbc/JdbcExecution.java` — current bounded queue, execution-mode separation, and executor ownership.
- `r2d1-filesystem/src/main/java/dev/nexcraft/r2d1/filesystem/FileSystemDocumentStore.java` — blocking work submitted to caller-owned executor and existing rejection mapping.
- `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1JdbcProperties.java` and `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1JdbcConfiguration.java` — existing flat JDBC keys and 4/64 defaults.
- `.github/workflows/ci.yml`, `website.yml`, `r2d1-integration-tests/build.gradle.kts` — exact unit, Java 25, website, and opt-in live integration verification commands.
- [Failsafe Bulkhead documentation](https://failsafe.dev/bulkhead/) and [Bulkhead API](https://failsafe.dev/javadoc/core/dev/failsafe/Bulkhead.html) — non-blocking permit acquisition/release and wait behavior; the R2D1 count-bounded pending queue must remain R2D1-owned.
- [Failsafe Maven Central coordinates](https://central.sonatype.com/artifact/dev.failsafe/failsafe) — version 3.3.2 and published dependency footprint.
- [AWS SDK Netty builder API](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/http/nio/netty/NettyNioAsyncHttpClient.Builder.html) — `maxConcurrency` and `maxPendingConnectionAcquires` have different meanings; client settings must not be inferred from connection count.
- [Cloudflare D1 limits](https://developers.cloudflare.com/d1/platform/limits/) and [Cloudflare API limits](https://developers.cloudflare.com/fundamentals/api/reference/limits/) — D1 execution characteristics and API request-rate quotas are distinct constraints.
