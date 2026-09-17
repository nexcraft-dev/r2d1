# Backpressure and Admission Control — Walkthrough

## Outcome

R2D1 now provides configurable, bounded admission control for downstream I/O across its R2, D1, JDBC, and Filesystem adapters. The changes are on `feat/backpressure-admission-control`, based on `release/1.7.0`. They remain uncommitted; no commit, push, pull request, tag, or release was created, as explicitly requested.

## Scope

- **Project:** R2D1
- **Plan slug:** `backpressure-admission-control`
- **Orchestration profile:** `STRICT`
- **Included work:** Core admission API and provider; R2, D1, JDBC, and Filesystem integration; Spring Boot and Micronaut configuration; bounded query/rebuild fan-out; tests and English documentation.
- **Explicitly excluded:** Live Cloudflare integration verification, which requires dedicated external test resources; all commit and publishing actions.

## What Changed

- Added a default budget of 8 active operations and 32 pending operations. Work beyond the configured budget fails with `AdmissionRejectedException`.
- Added independent limits for R2, D1, JDBC, and Filesystem downstream operations.
- Added global and per-adapter settings in Spring Boot and Micronaut, with per-field inheritance and legacy JDBC precedence.
- Bounded persistence query and rebuild fan-out. A rebuild prepares its first page before clearing D1.
- Sized owned R2 client capacity from effective concurrency while preserving explicit overrides; borrowed clients remain untouched.
- Preserved existing public constructors, the asynchronous SPI, caller-owned executors and clients, and JDBC execution modes.
- Updated module READMEs and six website pages.

## Why It Changed

Before this change, R2D1 did not provide a configurable admission budget at these downstream adapter boundaries. Concurrent work could exceed the capacity an application intended to dedicate to downstream I/O.

The implementation adds bounded active and pending work at each adapter, reports overload explicitly, and exposes independent limits through the supported framework integrations. Each adapter retains its own budget so a slow backend does not consume another adapter's allowance.

## Important Technical Details

- **Architecture and ownership:** Each adapter receives its own effective `BackpressureConfig`. Caller-owned executors, R2 clients, and HTTP clients are not closed or replaced by admission control.
- **Contracts:** Defaults are 8 active and 32 pending operations. Additional submissions are rejected with `AdmissionRejectedException`.
- **Cancellation:** JDBC cancellation does not release an admission permit until the submitted callable finishes.
- **Rebuild behavior:** The first page is prepared before D1 is cleared. A later rejection after the clear can leave a partial rebuild; this remains a known limitation.
- **Compatibility:** Existing public constructors and the async SPI remain available. Framework adapters preserve legacy JDBC configuration precedence.

## Major Files and Modules

- `r2d1/src/main/java/dev/nexcraft/r2d1/BackpressureConfig.java` and `AdmissionController.java` — core limits and admission API.
- `r2d1/src/main/java/dev/nexcraft/r2d1/r2/R2DocumentStore.java` and `r2d1/src/main/java/dev/nexcraft/r2d1/d1/D1IndexStore.java` — R2 and D1 operation admission.
- `r2d1-jdbc/src/main/java/dev/nexcraft/r2d1/jdbc/JdbcExecution.java` — JDBC admission lifecycle and executor integration.
- `r2d1-filesystem/src/main/java/dev/nexcraft/r2d1/filesystem/FileSystemDocumentStore.java` — filesystem operation admission.
- `r2d1-spring-boot-autoconfigure/src/main/java/dev/nexcraft/r2d1/spring/R2D1BackpressureProperties.java` and `r2d1-micronaut/src/main/java/dev/nexcraft/r2d1/micronaut/R2D1BackpressureConfiguration.java` — framework configuration.
- `r2d1/src/test/`, `r2d1-jdbc/src/test/`, `r2d1-filesystem/src/test/`, `r2d1-spring-boot-autoconfigure/src/test/`, and `r2d1-micronaut/src/test/` — lifecycle, adapter, compatibility, and configuration tests.
- `README.md`, module READMEs, and `website/src/pages/docs/` — usage and configuration documentation.

## Verification

### Unit scope

- **Status:** PASS
- **Command:** `GRADLE_USER_HOME=/tmp/r2d1-gradle-home ./gradlew --no-daemon check`
- **Outcome:** Repository-wide Gradle `check` passed.
- **Evidence:** Successful Gradle task result.

### Component or integration scope

- **Status:** PASS
- **Command:** `GRADLE_USER_HOME=/tmp/r2d1-gradle-home ./gradlew --no-daemon -Pr2d1.javaToolchainVersion=25 :r2d1-jdbc:check :r2d1-micronaut:check :r2d1-spring-boot-autoconfigure:check :r2d1-spring-boot-starter:check`
- **Outcome:** All four Java 25 module checks passed.
- **Evidence:** Successful Gradle task result.

- **Status:** PASS
- **Command:** `npm run check` (from `website`)
- **Outcome:** 25 files checked; zero errors, warnings, or hints.
- **Evidence:** Website checker output.

- **Status:** PASS
- **Command:** `GRADLE_USER_HOME=/tmp/r2d1-gradle-home ./gradlew --no-daemon -Pr2d1.javaToolchainVersion=25 :r2d1-micronaut:spotlessApply :r2d1-micronaut:javadoc`
- **Outcome:** Spotless and Javadoc passed; no Javadoc warnings.
- **Evidence:** Successful Gradle task result.

- **Status:** PASS
- **Command:** `git diff --check`
- **Outcome:** No whitespace errors.
- **Evidence:** Successful command result.

- **Status:** N/A
- **Command:** Live Cloudflare integration test
- **Outcome:** Not run; the test requires dedicated external test resources.
- **Evidence:** Environment requirement.

## Review

- **Report:** `docs/plan/backpressure-admission-control/review.md`
- **Verdict:** PASS — Main review found no blocking findings.
- **Resolved blocking findings:** None.
- **Residual findings:** No blocking findings; the accepted rebuild limitation and unrun Cloudflare integration test are listed under Risks and Follow-ups.
- **Coverage:** Core lifecycle, adapter boundaries, configuration precedence, resource ownership, public compatibility, tests, and documentation. The complete review Worker bundle exceeded 48 KB, so the review ran in Main.

## Risks and Follow-ups

- A rejection after a rebuild has cleared D1 can leave a partial rebuild. A first-page rejection occurs before the clear.
- Live Cloudflare integration tests remain unrun until the dedicated external test resources are available.
- Over-budget work now fails explicitly with `AdmissionRejectedException`; applications should configure limits for their downstream capacity.

## Release Metadata

The following metadata is proposed for later use only. No commit or pull request was created.

### Commit Message

```text
feat(backpressure): add bounded downstream I/O admission control
```

### Pull Request Title

```text
feat(backpressure): add bounded downstream I/O admission control
```

### Pull Request Description

```markdown
## Summary
- Add configurable active and pending admission limits for R2, D1, JDBC, and Filesystem I/O.
- Expose independent limits through Spring Boot and Micronaut and bound query/rebuild fan-out.

## Why

### Before
- R2D1 did not provide configurable admission budgets at these downstream adapter boundaries.

### Root cause
- Downstream operations were not governed by a shared bounded admission contract at the adapter boundaries.

## What changed
- Added the core admission API, provider, overload rejection, and adapter integrations.
- Added framework configuration, lifecycle/compatibility tests, and documentation.

## Compatibility and impact
- Public API/SPI: Existing constructors and the asynchronous SPI remain available; new configuration is additive.
- Data or migration: N/A; no persisted data format changed.
- User-facing behavior: Over-budget operations fail with `AdmissionRejectedException`; defaults are 8 active and 32 pending.
- Backward compatibility: Existing constructors, execution modes, and caller-owned resource behavior are preserved.
- Risk or rollback: A rejection after D1 is cleared during rebuild can leave a partial rebuild; revert the change to restore the prior behavior.

## Verification

| Scope | Command | Result | Evidence |
|---|---|---|---|
| Repository | `GRADLE_USER_HOME=/tmp/r2d1-gradle-home ./gradlew --no-daemon check` | PASS | Successful Gradle task result |
| Java 25 adapters | `GRADLE_USER_HOME=/tmp/r2d1-gradle-home ./gradlew --no-daemon -Pr2d1.javaToolchainVersion=25 :r2d1-jdbc:check :r2d1-micronaut:check :r2d1-spring-boot-autoconfigure:check :r2d1-spring-boot-starter:check` | PASS | Successful Gradle task result |
| Website | `npm run check` from `website` | PASS | 25 files, zero errors, warnings, or hints |
| Micronaut formatting/Javadoc | `GRADLE_USER_HOME=/tmp/r2d1-gradle-home ./gradlew --no-daemon -Pr2d1.javaToolchainVersion=25 :r2d1-micronaut:spotlessApply :r2d1-micronaut:javadoc` | PASS | No Javadoc warnings |
| Diff formatting | `git diff --check` | PASS | No whitespace errors |
| Live Cloudflare integration | N/A | N/A | Not run; dedicated external test resources are required |

## Review notes
- Main review found no blocking findings. Mid-rebuild rejection can leave a partial rebuild.

## Related issues
- N/A

## Release notes
- User-facing change: YES
- Release note: R2D1 now supports configurable bounded admission control for downstream I/O, with defaults of 8 active and 32 pending operations.
```

## Links

- Plan: `docs/plan/backpressure-admission-control/plan.md`
- State: `docs/plan/backpressure-admission-control/state.json`
- Review: `docs/plan/backpressure-admission-control/review.md`
