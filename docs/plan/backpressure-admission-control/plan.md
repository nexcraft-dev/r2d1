# Plan — R2D1 Backpressure and Admission Control

## Goal

Add an R2D1-owned asynchronous admission controller that caps concurrent downstream operations and bounds queued work. Apply independent inherited budgets to R2, D1, JDBC, and Filesystem; derive the R2D1-owned Netty client capacity from R2 admission; integrate settings into Spring Boot and Micronaut; and document local overload behavior without changing persistence ordering or adding rate limiting.

## Scope

- In scope:
  - Add `BackpressureConfig` with 8/32 library defaults, an R2D1-owned `AdmissionController`, a provider boundary with Failsafe 3.3.2 as the default implementation, and a distinct `AdmissionRejectedException`.
  - Bound each R2, D1, JDBC, and Filesystem I/O operation; hold permits until the operation stage is terminal; handle cancellation, synchronous throws, failures, queue cancellation, and completion races.
  - Resolve each adapter's effective settings as adapter override, then global setting, then R2D1 default; keep adapter budgets independent. Preserve existing flat JDBC keys as explicit legacy aliases.
  - Derive the R2D1-owned S3/Netty client's `maxConcurrency` from R2 admission and allow an explicit `r2d1.r2.client.max-concurrency` override. Warn and honor an explicit capacity below R2 admission. Never mutate or introspect caller-owned clients/resources.
  - Add deterministic unit and framework tests for capacity, permit lifecycle, fan-out, inheritance, ownership, and unchanged execution-mode behavior.
  - Update user documentation in English, including overload/rebuild behavior and the distinction between concurrency control and Cloudflare request-rate limits.
- Out of scope:
  - Rate limiting, retries, locks, adaptive control, reactive-streams demand, executor creation outside existing JDBC ownership, new deployment/transaction semantics, or changing index/query limits.
  - Commit, push, PR, tag, or release.

## Constraints

- The orchestrator will not push.
- Leave all implementation and plan artifacts in the working tree; do not stage or commit because the user explicitly prohibited commit and release actions.
- Base branch: `release/1.7.0`; integration branch: `feat/backpressure-admission-control` at the verified release head.
- Preserve existing public constructors and `R2Config` record components; additions must be additive overloads/types. Avoid `Optional` method parameters.
- Keep R2/D1/JDBC/Filesystem I/O asynchronous where the SPI is asynchronous. `StageSupport.await()` remains the only production blocking boundary.
- Keep `PLATFORM_THREAD` as JDBC default and `VIRTUAL_THREAD` opt-in. Admission limits remain separate from execution mode and caller-owned `DataSource` capacity.
- Do not resize, close, reflect on, or otherwise mutate caller-owned clients, executors, `DataSource`, or framework resources.
- Repository code, comments, Javadocs, tests, and docs remain English.
- Query/rebuild fan-out larger than an adapter's `maxConcurrency + maxPending` can be rejected. Keep the existing page size and prepare the first rebuild page before clearing D1; document this overload outcome.

## Orchestration Profile

- Profile: STRICT
- Risk level: HIGH
- Risk signals: public API additions, concurrency and cancellation races, persistence partial-failure semantics, optional provider class loading, several adapters/frameworks, and multiple configuration hierarchies.
- Delegated phases:
  - Research: MAIN (the router selected CLI_WORKER, but the first request failed before returning evidence; Main completed research fallback).
  - Implementation: CLI_WORKER_FIRST_WITH_MAIN_FALLBACK.
  - Testing: MAIN.
  - Review: CLI_WORKER_FIRST_WITH_MAIN_FALLBACK.
  - Walkthrough: WORKER.
- Rationale: Separate the provider/controller state machine, adapter boundaries, framework binding, and docs into sequential, independently verifiable tasks. This limits each patch while Main retains architecture, compatibility, test, and Git decisions. The same CLI Worker provider initialization failure may require Main fallback.

## Repository Evidence

- Research input: `docs/plan/backpressure-admission-control/research.md`
- Relevant modules and entry points:
  - `r2d1` — public configuration/controller and R2/D1 boundaries.
  - `r2d1-jdbc` — current bounded queue and execution-resource lifecycle.
  - `r2d1-filesystem` — caller-executor dispatch for blocking filesystem I/O.
  - `r2d1-spring-boot-autoconfigure` — global/adapter binding and bean construction.
  - `r2d1-micronaut` — conditional configuration and optional JDBC factory.
  - `website/src/pages/docs` and module `README.md` files — supported configuration and adapter guidance.
- Existing contracts and public APIs:
  - `DocumentStore` / `IndexStore` return `CompletionStage`; admission is per downstream operation.
  - `StageSupport.await()` is the only production blocking boundary. D1 failure after an R2 write/delete may remain a `PersistenceException.PartialFailure` with `AdmissionRejectedException` as cause.
  - R2/D1/framework resource ownership stays unchanged. Caller-owned S3 client, HTTP client, `DataSource`, and executor settings are not altered.
  - `JdbcExecutionMode` remains independent from capacity. Existing flat JDBC property keys remain valid explicit overrides.
  - Failsafe types stay behind `AdmissionProvider`; an alternate provider can be passed through the R2D1-owned provider contract without exposing Failsafe in public method signatures.
- Existing test and build conventions:
  - Root CI runs `./gradlew build` and `./gradlew check`.
  - Java 25 adapter CI runs `./gradlew -Pr2d1.javaToolchainVersion=25 :r2d1-jdbc:check :r2d1-micronaut:check :r2d1-spring-boot-autoconfigure:check :r2d1-spring-boot-starter:check`.
  - Website CI runs `npm run check` from `website`.
  - `r2d1-integration-tests:integrationTest` requires dedicated Cloudflare credentials and an explicit dedicated-resource confirmation.

## Standards Profile

- Selected standards:
  - `/Users/sean/.codex/skills/java-orchestrator/references/constitution/core/coding-standards.md`
  - `/Users/sean/.codex/skills/java-orchestrator/references/constitution/core/testing-and-quality.md`
- Service classification: none
- Non-negotiable requirements carried into this plan:
  - English Javadocs on public APIs; final fields and constructor injection where applicable; no method longer than 50 lines or more than three parameters.
  - Deterministic tests use controlled stages, latches, or barriers instead of sleep-based timing; every changed behavior has happy-path and failure-path coverage.
  - No network calls from unit tests; preserve borrowed-resource ownership and the async SPI.

## Verification Plan

### Unit scope
- Status: REQUIRED
- Working directory: `/Users/sean/Documents/projects/r2d1`
- Command: `./gradlew check`
- Gradle task: `check`
- Expected JUnit XML: `<module>/build/test-results/{test,optionalAdapterTest}/TEST-*.xml`
- Expected HTML report: `<module>/build/reports/tests/{test,optionalAdapterTest}/index.html`
- Discovery evidence: `.github/workflows/ci.yml`, root `build.gradle.kts`, and `r2d1-micronaut/build.gradle.kts` (which wires `optionalAdapterTest` into `check`).

### Component or integration scope
- Status: REQUIRED
- Working directory: `/Users/sean/Documents/projects/r2d1`
- Command: `./gradlew :r2d1-integration-tests:integrationTest`
- Gradle task: `integrationTest`
- Expected JUnit XML: `r2d1-integration-tests/build/test-results/integrationTest/TEST-*.xml`
- Expected HTML report: `r2d1-integration-tests/build/reports/tests/integrationTest/index.html`
- Discovery evidence: `r2d1-integration-tests/build.gradle.kts`; requires dedicated Cloudflare credentials and `R2D1_IT_CONFIRM_DEDICATED_RESOURCES=true`. If those resources are unavailable, record N/A with this evidence.

### Focused task verification

- T01: `./gradlew :r2d1:test`
- T02: `./gradlew :r2d1:test`
- T03–T04: `./gradlew :r2d1:test`
- T05: `./gradlew :r2d1-jdbc:test`
- T06: `./gradlew :r2d1-filesystem:test`
- T07–T08: `./gradlew :r2d1-spring-boot-autoconfigure:test`
- T09–T10: `./gradlew -Pr2d1.javaToolchainVersion=25 :r2d1-micronaut:check`
- T11–T13: `./gradlew check`; for website pages, also run `npm run check` from `website`.
- Final CI-equivalent checks: `./gradlew check` and `./gradlew -Pr2d1.javaToolchainVersion=25 :r2d1-jdbc:check :r2d1-micronaut:check :r2d1-spring-boot-autoconfigure:check :r2d1-spring-boot-starter:check`.

## Tasks

### T01 — Core admission API, bounded controller, and Failsafe provider
- Depends on: none
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Establish one R2D1-owned contract and controller before changing adapters.
- Worker specification:
  - Add immutable `BackpressureConfig` with validated defaults 8/32; add `AdmissionRejectedException` distinct from `StorageException`; add `AdmissionController` and `AdmissionProvider` without any Failsafe type in their public signatures.
  - The R2D1 controller owns non-blocking bounded pending admission and queue lifecycle. Failsafe 3.3.2 is the default internal provider and supplies permits only; do not copy or adapt Failsafe source. Keep the Failsafe implementation in its own package and use the provider boundary so callers can supply another provider.
  - Retain permits until the submitted stage completes, fails, or is canceled. Release exactly once after supplier throws or stage terminal completion; cancellation of queued work removes it, while cancellation of running work cannot free capacity before the underlying operation stage is terminal. No waits, executors, common pool, or `.get()`/`.join()`.
  - Keep unrelated Core API class loading independent of Failsafe. Preserve JSpecify/package conventions and add deterministic tests for capacity, overflow, completion lifecycle, cancellation, sync throw, races, and default/provider behavior.
- Worker inputs:
  - `r2d1/build.gradle.kts` — dependency conventions and version catalog/BOM use.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/spi/StorageException.java` — keep local rejection separate from storage failures.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/spi/package-info.java` — public SPI nullness and documentation conventions.
  - `r2d1/src/test/java/dev/nexcraft/r2d1/PublicApiSynchronousContractTest.java` — public API and failure-boundary test style.
  - `r2d1/src/test/java/dev/nexcraft/r2d1/internal/persistence/StageSupportTest.java` — completion-stage test conventions.
- Authorized outputs:
  - modify — `r2d1/build.gradle.kts`
  - create — `r2d1/src/main/java/dev/nexcraft/r2d1/BackpressureConfig.java`
  - create — `r2d1/src/main/java/dev/nexcraft/r2d1/AdmissionController.java`
  - create — `r2d1/src/main/java/dev/nexcraft/r2d1/spi/AdmissionProvider.java`
  - create — `r2d1/src/main/java/dev/nexcraft/r2d1/spi/AdmissionRejectedException.java`
  - create — `r2d1/src/main/java/dev/nexcraft/r2d1/internal/backpressure/AdmissionControllerCore.java`
  - create — `r2d1/src/main/java/dev/nexcraft/r2d1/internal/backpressure/failsafe/FailsafeAdmissionProvider.java`
  - create — `r2d1/src/main/resources/META-INF/services/dev.nexcraft.r2d1.spi.AdmissionProvider`
  - create — `r2d1/src/test/java/dev/nexcraft/r2d1/BackpressureConfigTest.java`
  - create — `r2d1/src/test/java/dev/nexcraft/r2d1/AdmissionControllerTest.java`
  - create — `r2d1/src/test/java/dev/nexcraft/r2d1/OptionalFailsafeClassLoadingTest.java`
- Focused tests:
  - Prove max concurrency and pending limits with controlled stages and no timing sleeps.
  - Prove permits persist through asynchronous completion and release once on success, failure, cancellation, or supplier throw; prove canceled queued work is removed and overflow is a distinct `AdmissionRejectedException`.
  - Prove alternative-provider use does not require loading Failsafe classes and unrelated Core public classes load when Failsafe is absent.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew :r2d1:test`
  - Expected evidence: `r2d1/build/test-results/test/TEST-*.xml` and `r2d1/build/reports/tests/test/index.html`.
- Acceptance criteria:
  - Admission never blocks a caller, pending work is count-bounded, each operation has exactly one terminal permit release, and only the internal provider imports Failsafe.
  - Failsafe is an implementation dependency at 3.3.2 with no direct transitive dependencies; public R2D1 APIs do not expose Failsafe types.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T02 — R2 admission and owned S3 client capacity
- Depends on: T01
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Apply the Core gate to each R2 SDK request and align the R2D1-owned Netty client.
- Worker specification:
  - Add compatible overloads accepting `BackpressureConfig`; preserve current constructors, client ownership, and `R2Config` record shape.
  - Gate each R2 `put/get/delete/list` operation. Rejection must bypass S3 failure mapping; hold each permit until that request's mapped asynchronous stage is terminal.
  - Derive an R2D1-owned Netty client's `maxConcurrency` from effective R2 admission. Add an explicit nullable override using AWS's `maxConcurrency` terminology; honor it and warn when lower than admission. Never reconfigure or inspect a caller-owned `S3AsyncClient`.
  - Verify the project-resolved AWS SDK 2.54.13 API before changing pending-acquisition settings; do not add an unneeded pending-acquire property. Preserve S3 error, list, and cancellation behavior.
- Worker inputs:
  - `r2d1/src/main/java/dev/nexcraft/r2d1/BackpressureConfig.java` — effective limits.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/AdmissionController.java` — R2D1 async admission API.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/spi/AdmissionRejectedException.java` — local rejection type.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/r2/R2Config.java` — preserve public record shape.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/r2/R2DocumentStore.java` — request mapping and ownership.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/r2/R2ClientFactory.java` — owned Netty client setup.
  - `r2d1/src/test/java/dev/nexcraft/r2d1/r2/R2DocumentStoreTest.java` — controlled S3 stage tests.
  - `r2d1/src/test/java/dev/nexcraft/r2d1/r2/R2ClientFactoryTest.java` — client configuration tests.
- Authorized outputs:
  - modify — `r2d1/src/main/java/dev/nexcraft/r2d1/r2/R2DocumentStore.java`
  - modify — `r2d1/src/main/java/dev/nexcraft/r2d1/r2/R2ClientFactory.java`
  - modify — `r2d1/src/test/java/dev/nexcraft/r2d1/r2/R2DocumentStoreTest.java`
  - modify — `r2d1/src/test/java/dev/nexcraft/r2d1/r2/R2ClientFactoryTest.java`
- Focused tests:
  - Verify concurrency/pending bounds, stage terminal permit release, sync throw/failure/cancellation, and distinct capacity rejection.
  - Verify owned-client max concurrency derives from admission, an explicit low value is warned and honored, and a caller-owned S3 client is not reconfigured or closed.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew :r2d1:test`
  - Expected evidence: `r2d1/build/test-results/test/TEST-*.xml` and `r2d1/build/reports/tests/test/index.html`.
- Acceptance criteria:
  - Every R2 request is independently admitted and no low-level default capacity falls below effective admission.
  - Borrowed-client and current R2 error semantics remain intact.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T03 — D1 transport admission
- Depends on: T02
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Gate every D1 REST operation at the common transport boundary, including schema initialization.
- Worker specification:
  - Add compatible overloads accepting `BackpressureConfig`; preserve existing constructors and caller-owned `HttpClient` ownership.
  - Wrap the transport supplied to `D1SchemaManager` and used for CRUD/query so each transport operation has its own permit. D1 owns its own controller, independent of R2.
  - Keep admission asynchronous, leave D1/rate-limit semantics distinct, and ensure rejection bypasses D1 storage-failure mapping. Do not mutate or inspect a caller-owned client.
- Worker inputs:
  - `r2d1/src/main/java/dev/nexcraft/r2d1/BackpressureConfig.java` — effective limits.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/AdmissionController.java` — R2D1 async admission API.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/spi/AdmissionRejectedException.java` — local rejection type.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/d1/D1IndexStore.java` — schema/store transport wiring.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/d1/internal/transport/D1Transport.java` — transport contract.
  - `r2d1/src/test/java/dev/nexcraft/r2d1/d1/D1IndexStoreTest.java` — controlled D1 stages and ownership.
- Authorized outputs:
  - modify — `r2d1/src/main/java/dev/nexcraft/r2d1/d1/D1IndexStore.java`
  - create — `r2d1/src/main/java/dev/nexcraft/r2d1/d1/internal/transport/AdmittedD1Transport.java`
  - modify — `r2d1/src/test/java/dev/nexcraft/r2d1/d1/D1IndexStoreTest.java`
- Focused tests:
  - Verify D1 max active/pending counts, independent R2/D1 limits, async completion release, rejection identity, and schema initialization passes through admission.
  - Verify caller-owned `HttpClient` remains open and unchanged; D1 request failures retain current mapping.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew :r2d1:test`
  - Expected evidence: `r2d1/build/test-results/test/TEST-*.xml` and `r2d1/build/reports/tests/test/index.html`.
- Acceptance criteria:
  - Every D1 REST transport operation, including initialization, is independently admitted and does not hold a permit during later R2 work.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T04 — Query fan-out and rebuild admission regression tests
- Depends on: T02, T03
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Prove the existing persistence pipeline is governed by adapter-level admission and retains its rebuild clear ordering.
- Worker specification:
  - Extend `ConsistencyRecoveryTest` with a controlled `DocumentStore` that delegates each operation through the R2D1 admission controller and an independently controlled `IndexStore`.
  - Assert D1 query completion releases its own permit before R2 fan-out; R2 active requests never exceed the limit, only `maxPending` waits, and further fan-out receives `AdmissionRejectedException`.
  - Cover a 100-key rebuild page exceeding default 8/32 and assert first-page admission failure occurs before `IndexStore.clear`. Preserve existing 205-document rebuild tests that use an unrestricted fake store.
  - Do not alter production persistence orchestration, query ordering, or rebuild page size in this test-only task.
- Worker inputs:
  - `r2d1/src/main/java/dev/nexcraft/r2d1/BackpressureConfig.java` — default and custom limits.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/AdmissionController.java` — per-operation test wrapper.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/spi/AdmissionRejectedException.java` — assertion type.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/internal/persistence/PersistentR2D1Collection.java` — query and rebuild fan-out behavior.
  - `r2d1/src/test/java/dev/nexcraft/r2d1/ConsistencyRecoveryTest.java` — existing persistence test fixtures and clear ordering.
- Authorized outputs:
  - modify — `r2d1/src/test/java/dev/nexcraft/r2d1/ConsistencyRecoveryTest.java`
- Focused tests:
  - Use manually completed futures/barriers, not sleeps, to prove R2 concurrency, bounded pending count, independent D1/R2 permit lifetimes, overload rejection, and first-page rebuild safety.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew :r2d1:test`
  - Expected evidence: `r2d1/build/test-results/test/TEST-*.xml` and `r2d1/build/reports/tests/test/index.html`.
- Acceptance criteria:
  - Query and rebuild fan-out cannot bypass R2 admission; D1 query capacity is released before R2 reads; rejection does not clear D1 before the first page is ready.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T05 — JDBC execution admission
- Depends on: T01
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Reuse the Core admission controller while preserving JDBC execution and resource lifecycle behavior.
- Worker specification:
  - Refactor `JdbcExecution` to delegate bounded admission to Core. Preserve all public factory signatures, `JdbcExecutionConfig`, platform/virtual thread behavior, caller-executor ownership, shutdown of only internally owned executors, and no DataSource mutation.
  - Surface capacity overflow as `AdmissionRejectedException`; keep executor submission failures mapped as today. Closing execution stops new work, rejects queued work, and allows submitted work to finish.
  - Ensure canceling an exposed result cannot release a permit while its blocking JDBC task still runs. Do not add threads, schedulers, or global executors.
- Worker inputs:
  - `r2d1/src/main/java/dev/nexcraft/r2d1/BackpressureConfig.java` — effective limits.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/AdmissionController.java` — bounded async gate.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/spi/AdmissionRejectedException.java` — local rejection type.
  - `r2d1-jdbc/src/main/java/dev/nexcraft/r2d1/jdbc/JdbcExecution.java` — current queue and lifecycle.
  - `r2d1-jdbc/src/main/java/dev/nexcraft/r2d1/jdbc/JdbcExecutionConfig.java` — existing execution-mode contract.
  - `r2d1-jdbc/src/test/java/dev/nexcraft/r2d1/jdbc/JdbcExecutionTest.java` — mode, queue, close, and rejection tests.
- Authorized outputs:
  - modify — `r2d1-jdbc/src/main/java/dev/nexcraft/r2d1/jdbc/JdbcExecution.java`
  - modify — `r2d1-jdbc/src/test/java/dev/nexcraft/r2d1/jdbc/JdbcExecutionTest.java`
- Focused tests:
  - Prove bounded active/pending work, distinct admission rejection versus executor failure, deterministic close behavior, and unchanged platform/virtual/caller-executor semantics.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew :r2d1-jdbc:test`
  - Expected evidence: `r2d1-jdbc/build/test-results/test/TEST-*.xml` and `r2d1-jdbc/build/reports/tests/test/index.html`.
- Acceptance criteria:
  - JDBC capacity uses the shared Core controller; execution mode, DataSource, executor ownership, and storage error mapping stay intact except for local capacity rejection.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T06 — Filesystem admission
- Depends on: T01
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Bound R2D1-submitted blocking filesystem work without taking ownership of the supplied executor.
- Worker specification:
  - Add `BackpressureConfig` constructor overloads to `FileSystemDocumentStore`; existing constructors use the safe 8/32 default.
  - Gate every filesystem operation before executor submission. Do not resize or close the supplied executor; preserve current filesystem error mapping and key/operation behavior.
  - Ensure cancellation cannot release a permit while the submitted file operation is still running. Do not add threads, schedulers, or global executors.
- Worker inputs:
  - `r2d1/src/main/java/dev/nexcraft/r2d1/BackpressureConfig.java` — effective limits.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/AdmissionController.java` — bounded async gate.
  - `r2d1/src/main/java/dev/nexcraft/r2d1/spi/AdmissionRejectedException.java` — local rejection type.
  - `r2d1-filesystem/src/main/java/dev/nexcraft/r2d1/filesystem/FileSystemDocumentStore.java` — blocking dispatch and failure mapping.
  - `r2d1-filesystem/src/test/java/dev/nexcraft/r2d1/filesystem/FileSystemDocumentStoreTest.java` — controlled executor tests.
- Authorized outputs:
  - modify — `r2d1-filesystem/src/main/java/dev/nexcraft/r2d1/filesystem/FileSystemDocumentStore.java`
  - modify — `r2d1-filesystem/src/test/java/dev/nexcraft/r2d1/filesystem/FileSystemDocumentStoreTest.java`
- Focused tests:
  - Use controlled executor tasks to prove active/pending limits, overflow rejection, cancellation lifecycle, executor rejection mapping, and borrowed-executor ownership without sleeps.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew :r2d1-filesystem:test`
  - Expected evidence: `r2d1-filesystem/build/test-results/test/TEST-*.xml` and `r2d1-filesystem/build/reports/tests/test/index.html`.
- Acceptance criteria:
  - Filesystem work observes the shared admission semantics while preserving executor ownership and existing storage behavior.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T07 — Spring Boot nested backpressure property model
- Depends on: T01
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Add explicit global, adapter, and R2 client properties while preserving current record constructors and flat JDBC property names.
- Worker specification:
  - Add nullable nested `backpressure` values to root/R2/D1/JDBC/Filesystem properties, plus `client.max-concurrency` under R2. Use typed nested property records and preserve old constructor signatures with overloads.
  - Keep flat `r2d1.jdbc.max-concurrency` and `max-pending` bindable as legacy explicit values; do not silently let their old defaults override global inheritance. Factory precedence is resolved in T05.
  - Test binding and absence independently; do not change bean creation in this task.
- Worker inputs:
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1Properties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1R2Properties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1D1Properties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1JdbcProperties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1FilesystemProperties.java`
  - `r2d1-spring-boot-autoconfigure/src/test/java/dev/nexcraft/r2d1/spring/SpringAutoConfigurationTest.java`
- Authorized outputs:
  - modify — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1Properties.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1R2Properties.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1D1Properties.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1JdbcProperties.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1FilesystemProperties.java`
  - create — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1BackpressureProperties.java`
  - create — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1R2ClientProperties.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/test/java/dev/nexcraft/r2d1/spring/SpringAutoConfigurationTest.java`
- Focused tests:
  - Bind global and adapter `.backpressure.*` keys and `r2d1.r2.client.max-concurrency`; verify unset values remain absent and all previous record constructors still compile.
  - Verify legacy flat JDBC values remain bindable without treating their default record values as user overrides.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew :r2d1-spring-boot-autoconfigure:test`
  - Expected evidence: `r2d1-spring-boot-autoconfigure/build/test-results/test/TEST-*.xml` and `build/reports/tests/test/index.html`.
- Acceptance criteria:
  - All requested Spring configuration paths bind while prior property accessors and constructors remain source-compatible.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T08 — Spring Boot inheritance, adapter factories, and warnings
- Depends on: T07
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Resolve typed property values and pass independent effective policies into Spring-created adapters.
- Worker specification:
  - Add one resolver with per-field precedence: new adapter `.backpressure.*`, explicit legacy flat JDBC value, global `.backpressure.*`, then Core defaults 8/32. Legacy aliases apply only to JDBC; new nested properties win over legacy flat values.
  - Wire R2/D1/JDBC/Filesystem factories to create one effective controller/config per adapter. Pass effective R2 config and optional S3 client override to the owned R2 constructor; log and honor an explicit override lower than admission.
  - Apply admission when Spring supplies caller-owned S3/HTTP clients, DataSource, or executor without mutating, resizing, or closing borrowed resources.
  - Add ApplicationContext tests for independent budgets, precedence, zero-config defaults, mismatch warning, caller-owned resources, and JDBC thread mode preservation.
- Worker inputs:
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1BackpressureProperties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1R2ClientProperties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1Properties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1R2Properties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1D1Properties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1JdbcProperties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1FilesystemProperties.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/R2Configuration.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/D1Configuration.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/JdbcConfiguration.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/FilesystemConfiguration.java`
  - `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/ConfigurationSupport.java`
  - `r2d1-spring-boot-autoconfigure/src/test/java/dev/nexcraft/r2d1/spring/SpringAutoConfigurationTest.java`
- Authorized outputs:
  - create — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/BackpressureConfigurationSupport.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/R2Configuration.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/D1Configuration.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/JdbcConfiguration.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/internal/FilesystemConfiguration.java`
  - modify — `r2d1-spring-boot-autoconfigure/src/test/java/dev/nexcraft/r2d1/spring/SpringAutoConfigurationTest.java`
- Focused tests:
  - Verify per-setting inheritance/override, distinct R2/D1/JDBC/Filesystem controller instances, flat JDBC alias handling, low-capacity warning, and that caller-provided beans remain open and unmodified.
  - Verify default and configured `PLATFORM_THREAD`/`VIRTUAL_THREAD` selection are unchanged.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew :r2d1-spring-boot-autoconfigure:test`
  - Expected evidence: `r2d1-spring-boot-autoconfigure/build/test-results/test/TEST-*.xml` and `build/reports/tests/test/index.html`.
- Acceptance criteria:
  - Spring-created adapters use resolved independent budgets; explicit low-level S3 overrides are honored with the required warning when lower than admission.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T09 — Micronaut nested backpressure property model
- Depends on: T01
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Add Micronaut binding for global, adapter, and owned S3 client settings without changing existing constructors.
- Worker specification:
  - Add nullable global and nested R2/D1/JDBC `.backpressure.*` settings and R2 `.client.max-concurrency` using Micronaut-compatible configuration records.
  - Preserve current record accessors and constructors with overloads. Keep legacy JDBC flat settings bindable; factory precedence is implemented in T07.
  - Test property binding, missing values, defaults, and previous constructor usage. Do not change bean creation in this task.
- Worker inputs:
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1Configuration.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1R2Configuration.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1D1Configuration.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1JdbcConfiguration.java`
  - `r2d1-micronaut/src/test/java/dev/nexcraft/r2d1/micronaut/ConfigurationContractTest.java`
- Authorized outputs:
  - modify — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1Configuration.java`
  - modify — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1R2Configuration.java`
  - modify — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1D1Configuration.java`
  - modify — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1JdbcConfiguration.java`
  - create — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1BackpressureConfiguration.java`
  - create — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1R2ClientConfiguration.java`
  - modify — `r2d1-micronaut/src/test/java/dev/nexcraft/r2d1/micronaut/ConfigurationContractTest.java`
- Focused tests:
  - Bind global and adapter values independently, bind the S3 override, verify unset values remain inheritable, and compile/use existing record constructors.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew -Pr2d1.javaToolchainVersion=25 :r2d1-micronaut:check`
  - Expected evidence: Micronaut `test` and `optionalAdapterTest` XML/HTML reports under `r2d1-micronaut/build`.
- Acceptance criteria:
  - Micronaut binds all global/adapter settings with no regression to the optional JDBC adapter classpath or disabled-by-default integration.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T10 — Micronaut inheritance and adapter factories
- Depends on: T09
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Apply effective limits in Micronaut-created adapters while retaining Java 25 and JDBC lifecycle behavior.
- Worker specification:
  - Add a Micronaut resolver with precedence: adapter nested value, explicitly supplied legacy JDBC flat value, global value, then Core default 8/32. Resolve each limit independently.
  - Pass separate effective config/controller instances to R2, D1, and JDBC factories. Apply the R2-owned client override and warning; never change caller-supplied resources.
  - Keep Micronaut 5 conditional bean selection, optional JDBC behavior, `PLATFORM_THREAD` default, and `VIRTUAL_THREAD` opt-in unchanged. Add contract tests for precedence and ownership.
- Worker inputs:
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1BackpressureConfiguration.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1R2ClientConfiguration.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1Configuration.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1R2Configuration.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1D1Configuration.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1JdbcConfiguration.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/internal/R2Factory.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/internal/D1Factory.java`
  - `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/internal/JdbcFactory.java`
  - `r2d1-micronaut/src/test/java/dev/nexcraft/r2d1/micronaut/ConfigurationContractTest.java`
- Authorized outputs:
  - create — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/internal/BackpressureConfigurationSupport.java`
  - modify — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/internal/R2Factory.java`
  - modify — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/internal/D1Factory.java`
  - modify — `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/internal/JdbcFactory.java`
  - modify — `r2d1-micronaut/src/test/java/dev/nexcraft/r2d1/micronaut/ConfigurationContractTest.java`
- Focused tests:
  - Verify settings inheritance/override, independent adapter instances, legacy JDBC aliases, S3 mismatch warning, caller-resource ownership, Java 25 execution modes, and missing optional JDBC behavior.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew -Pr2d1.javaToolchainVersion=25 :r2d1-micronaut:check`
  - Expected evidence: Micronaut `test` and `optionalAdapterTest` reports under `r2d1-micronaut/build`.
- Acceptance criteria:
  - Micronaut-created adapters resolve and use independent settings; no optional module or thread-mode contract regresses.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T11 — Website configuration and adapter documentation
- Depends on: T02, T03, T04, T05, T06, T08, T10
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Document the delivered behavior where users configure and operate R2D1.
- Worker specification:
  - Explain zero-config defaults 8/32 as R2D1 policy, per-adapter inheritance/override, explicit JDBC legacy aliases, AWS `max-concurrency`, and low-capacity warning.
  - Explain bounded pending/rejection, per-downstream-operation permits, cancellation lifecycle, query/rebuild fan-out overflow, and first-page-before-clear rebuild safety.
  - Distinguish concurrency admission from Cloudflare API rate limits and state that “backpressure” here is not a Reactive Streams protocol. Do not imply 8 is an official Cloudflare limit or add a rate limiter.
- Worker inputs:
  - `website/src/pages/docs/configuration.md`
  - `website/src/pages/docs/micronaut.md`
  - `website/src/pages/docs/document-stores/r2.md`
  - `website/src/pages/docs/document-stores/filesystem.md`
  - `website/src/pages/docs/index-stores/d1.md`
  - `website/src/pages/docs/index-stores/jdbc.md`
- Authorized outputs:
  - modify — `website/src/pages/docs/configuration.md`
  - modify — `website/src/pages/docs/micronaut.md`
  - modify — `website/src/pages/docs/document-stores/r2.md`
  - modify — `website/src/pages/docs/document-stores/filesystem.md`
  - modify — `website/src/pages/docs/index-stores/d1.md`
  - modify — `website/src/pages/docs/index-stores/jdbc.md`
- Focused tests:
  - Check property spelling and defaults against implemented configuration, confirm internal links and snippets, and run the website check.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew check`
  - Expected evidence: full check reports; additionally `npm run check` from `website` validates the edited website pages.
- Acceptance criteria:
  - The docs make default/inheritance/override behavior and rejection consequences accurate and understandable without describing a rate limiter or reactive-streams implementation.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T12 — Root and framework module README updates
- Depends on: T11
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Keep short install/framework guides aligned with the canonical website docs.
- Worker specification:
  - Update only relevant configuration snippets and links in root, Micronaut, Spring autoconfigure, and Spring starter READMEs. Avoid duplicating the entire configuration reference.
  - Preserve existing setup, dependency, ownership, and optional-adapter instructions. Use the final property paths and mention only confirmed defaults and warning behavior.
- Worker inputs:
  - `README.md`
  - `r2d1-micronaut/README.md`
  - `r2d1-spring-boot-autoconfigure/README.md`
  - `r2d1-spring-boot-starter/README.md`
- Authorized outputs:
  - modify — `README.md`
  - modify — `r2d1-micronaut/README.md`
  - modify — `r2d1-spring-boot-autoconfigure/README.md`
  - modify — `r2d1-spring-boot-starter/README.md`
- Focused tests:
  - Check examples against Spring/Micronaut property contracts and verify referenced docs paths exist.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew check`
  - Expected evidence: full check reports; README links/examples reviewed against the tested property contract.
- Acceptance criteria:
  - Root/framework README snippets are accurate and point readers to the canonical configuration reference.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

### T13 — JDBC and Filesystem module README updates
- Depends on: T05, T06, T11
- Planned executor: CLI_WORKER
- Worker eligibility: ELIGIBLE
- Context: Update module-level adapter docs to reflect effective admission limits and ownership boundaries.
- Worker specification:
  - Update only the JDBC and Filesystem configuration/execution sections. Clarify that JDBC execution mode and admission are independent, a caller-owned DataSource/executor is not resized, and Filesystem admission bounds submitted work without taking executor ownership.
  - Preserve existing database/backend caveats and examples; link to canonical global configuration docs for inheritance and override details.
- Worker inputs:
  - `r2d1-jdbc/README.md`
  - `r2d1-filesystem/README.md`
- Authorized outputs:
  - modify — `r2d1-jdbc/README.md`
  - modify — `r2d1-filesystem/README.md`
- Focused tests:
  - Check property names and examples against implementation and verify links.
- Main verification:
  - Working directory: `/Users/sean/Documents/projects/r2d1`
  - Command: `./gradlew check`
  - Expected evidence: full check reports; module README examples checked against configuration tests.
- Acceptance criteria:
  - Module guides state the changed capacity defaults/behavior accurately and preserve existing ownership and execution-mode guidance.
- Fallback:
  - Any ineligible request, timeout, provider failure, `needs_main`, invalid result, or patch validation failure causes automatic Main implementation without a Worker retry.
- Result:
  - Status: TODO
  - Actual executor: null
  - Worker request ID: null
  - Fallback reason: null
  - Result notes: ""

## Execution Gates

- Research: the router selected `CLI_WORKER`; the first bounded request failed during provider initialization, so Main completed research without retry.
- Implementation: after user approval, run tasks sequentially in dependency order. For each eligible task, try its planned CLI Worker request once; on failure, record the fallback and complete it in Main without retrying that task.
- Review: after required local verification, request one CLI Worker review using the fixed Sol/low route; if provider execution or evidence validation fails, review in Main without retry.
- Walkthrough: run the required CLI Worker walkthrough after blocking review findings are cleared; validate any patch before applying it. If the mandatory Worker remains unavailable, stop at the Walkthrough failure gate and request direction.
- Finalization: do not stage or commit. This explicit user instruction overrides the orchestrator template's default automatic commit step. Report `git diff --stat` and `git status`.
- Plan approval: this plan is awaiting the user's approval. Executor routing is not a user choice.

## Global Acceptance

- All T01–T13 acceptance criteria are met and all integrated diffs are reviewed by Main.
- `./gradlew check`, the Java 25 adapter check, and website `npm run check` pass, or any unavailable scope is recorded as N/A with its evidence.
- Live Cloudflare `integrationTest` is run only if the dedicated-resource environment and confirmation are available; otherwise report N/A, not pass.
- `docs/plan/backpressure-admission-control/review.md` has no open blocking findings.
- Walkthrough is complete and validated before final reporting. No commit or other release action occurs.

## Risks and Impact

- Query or rebuild R2 fan-out above `maxConcurrency + maxPending` now fails locally. Default 8/32 admits at most 40 operations per adapter instance at a time; current 100-key rebuild pages can therefore reject. The first page is prepared before D1 clear, so a first-page rejection does not clear the index; later-page rejection can leave a partially rebuilt projection, as other rebuild failures already can.
- JDBC framework zero-config effective defaults move from the current flat-property defaults 4/64 to the requested inherited R2D1 defaults 8/32. Explicit legacy flat values remain supported as overrides. A `DataSource` pool may have less usable capacity than admission; R2D1 will not introspect or resize it.
- AWS SDK `maxConcurrency` is not synonymous with a physical connection count; Netty `maxPendingConnectionAcquires` is separate. Confirm the resolved 2.54.13 builder API before changing pending-acquisition settings. Only warn on an explicit low S3 capacity; honor it.
- Failsafe's permit API does not replace R2D1's count-bounded pending queue. The Core controller must own that queue, cancellation removal, and exact-once terminal release.
- Documentation and verification must keep Cloudflare's D1 execution characteristics and API rate quotas separate. No rate limiter is included.
- The CLI Worker initialization failure may recur; all non-gated eligible work falls back to Main, while mandatory Walkthrough failure requires direction.

## References

- `docs/plan/backpressure-admission-control/research.md`
- `r2d1/src/main/java/dev/nexcraft/r2d1/internal/persistence/StageSupport.java`
- `r2d1/src/main/java/dev/nexcraft/r2d1/internal/persistence/PersistentR2D1Collection.java`
- `r2d1-jdbc/src/main/java/dev/nexcraft/r2d1/jdbc/JdbcExecution.java`
- `r2d1-filesystem/src/main/java/dev/nexcraft/r2d1/filesystem/FileSystemDocumentStore.java`
- [Failsafe Bulkhead documentation](https://failsafe.dev/bulkhead/)
- [AWS SDK Netty async client builder](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/http/nio/netty/NettyNioAsyncHttpClient.Builder.html)
- [Cloudflare D1 limits](https://developers.cloudflare.com/d1/platform/limits/)
- [Cloudflare API limits](https://developers.cloudflare.com/fundamentals/api/reference/limits/)
