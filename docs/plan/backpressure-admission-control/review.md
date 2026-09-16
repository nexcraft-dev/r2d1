# Review: Backpressure and Admission Control

## Result

No blocking findings. Main reviewed the complete integrated diff and approved plan after the
read-only Worker review bundle exceeded the Runner's 48 KB input limit. The bounded review evidence
would have been incomplete, so no partial Worker request was sent.

The integrated diff bundle is approximately 173,562 bytes across tracked changes and new source
files; the approved plan is 50,127 bytes. Per the orchestration review gate, review therefore
fell back to Main.

## Review coverage

- Core admission limits, provider boundary, bounded FIFO pending queue, close behavior, and
  cancellation lifecycle.
- R2, D1, JDBC, and Filesystem adapter boundaries, including permit lifetime and caller-owned
  resource ownership.
- Spring Boot and Micronaut per-field inheritance, explicit legacy JDBC aliases, and owned versus
  borrowed R2 client capacity behavior.
- Query and rebuild fan-out, including first-page-before-clear rebuild safety.
- Public constructors, existing JDBC execution-mode behavior, generated configuration metadata,
  tests, website documentation, and module READMEs.

No API or resource-ownership regression was found in the reviewed diff. Intentional overload
behavior is documented: work beyond an adapter's active plus pending budget fails with
`AdmissionRejectedException`. A rejection after a rebuild has cleared its destination can still
leave a partial rebuild, while a first-page rejection occurs before the clear.

## Verification evidence

| Scope | Command | Result |
|---|---|---|
| Repository | `GRADLE_USER_HOME=/tmp/r2d1-gradle-home ./gradlew --no-daemon check` | PASS |
| Java 25 adapters | `GRADLE_USER_HOME=/tmp/r2d1-gradle-home ./gradlew --no-daemon -Pr2d1.javaToolchainVersion=25 :r2d1-jdbc:check :r2d1-micronaut:check :r2d1-spring-boot-autoconfigure:check :r2d1-spring-boot-starter:check` | PASS |
| Website | `npm run check` from `website` | PASS; 25 files, 0 errors, warnings, or hints |
| Micronaut Javadoc | `GRADLE_USER_HOME=/tmp/r2d1-gradle-home ./gradlew --no-daemon -Pr2d1.javaToolchainVersion=25 :r2d1-micronaut:spotlessApply :r2d1-micronaut:javadoc` | PASS; no Javadoc warnings |
| Diff formatting | `git diff --check` | PASS |

Live Cloudflare integration tests were not run; they require the dedicated external test resources.

## Delivery state

Branch: `feat/backpressure-admission-control`, based on `release/1.7.0`. Changes remain uncommitted;
no commit, push, pull request, tag, or release action was performed.
