# R2D1 release guide

R2D1 publishes its reusable Java libraries to Maven Central through the Sonatype Central Portal.
The release process is assembled on a versioned release branch. Merging its pull request into
`main` starts the automated verification, tagging, and publication workflow.

## Published artifacts

The workflow publishes these six artifacts with the same version:

- `dev.nexcraft:r2d1` — core API with the Cloudflare R2 and D1 adapters
- `dev.nexcraft:r2d1-filesystem` — filesystem `DocumentStore` adapter
- `dev.nexcraft:r2d1-jdbc` — H2, HSQLDB, and SQLite `IndexStore` adapter
- `dev.nexcraft:r2d1-micronaut` — Micronaut 5 integration
- `dev.nexcraft:r2d1-spring-boot-autoconfigure` — Spring Boot 4 auto-configuration
- `dev.nexcraft:r2d1-spring-boot-starter` — Spring Boot 4 convenience starter depending on the auto-configuration

The repository root and `r2d1-integration-tests` are not published artifacts. The legacy
`r2d1-core`, `r2d1-d1`, and `r2d1-r2` coordinates remain untouched; new releases do not delete or
overwrite them.

## Branch and tag lifecycle

For the `1.7.0` release, use this lifecycle:

1. Create `release/1.7.0` from the current `main` commit.
2. Create feature branches, such as `feature/spring-integration`, from `release/1.7.0`.
3. Merge completed feature branches into `release/1.7.0`. Use that branch as the integration and
   stabilization branch for the release.
4. Open a pull request from `release/1.7.0` into `main` and merge it after the release checks pass.
5. The `maven-central-release.yml` workflow runs for a merged `release/<major>.<minor>.<patch>` PR
   into `main`. It derives the version from the release branch name, verifies the merge commit is
   reachable from `main`, runs the verification and local publication checks, and creates the
   matching annotated tag (for example, `v1.7.0`).
6. After creating the tag, the same workflow publishes all six artifacts to Maven Central. A separate
   dependent job creates the GitHub Release only after Central publication succeeds.

Feature PRs into a release branch, unmerged/closed PRs, and PRs from non-release branches do not
publish. A push to `main` outside a merged `release/<version>` PR does not publish. The workflow
does not auto-increment versions: the release branch name is the version source, so the next cycle
uses a new branch such as `release/1.8.0`.

The release tag is pushed by the workflow with `GITHUB_TOKEN`; tag creation and publication happen
in the same run. This avoids relying on a second workflow run from the tag push.

## Required secrets

Configure these repository secrets before the first production release:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `GPG_PRIVATE_KEY`
- `GPG_PASSPHRASE`

The expected public signing key ID is `3D84F8C994DA0DBB`. Gradle derives the signing key ID from
the in-memory key and signs every published artifact file.

## Local preflight

Use the release version explicitly when checking the publication surface locally:

```bash
./gradlew -Pr2d1.version=1.7.0 clean check build javadoc
./gradlew -Pr2d1.version=1.7.0 publishToMavenLocal
```

Inspect the six Maven Local directories under `~/.m2/repository/dev/nexcraft/`. Each release
directory must contain the main JAR, Gradle module metadata, sources JAR, Javadoc JAR, POM, and
detached ASCII-armored signatures. Review the generated POMs and Gradle metadata for optional
adapter leakage before merging the release pull request into `main`.

The default development version is `1.7.0-SNAPSHOT` in `gradle.properties`. The workflow derives
the production version from the merged release branch name and passes that exact non-SNAPSHOT
version with `-Pr2d1.version`, so the tag and published coordinates cannot silently diverge.

## Production workflow boundary

The publish job runs the complete verification build, validates Central credentials, publishes all
six artifacts to Maven Local, checks their metadata and signatures, and then creates the release tag
and submits the complete batch to Maven Central. The GitHub Release job depends on that publish job
and creates the release only after Central publication succeeds.

If verification or local artifact validation fails, no tag is created; fix the release branch and
merge an updated release PR. If Maven Central publication fails after tagging, keep the tag
unchanged and confirm Central did not accept the version before retrying. If code changes are needed
after tagging, use a new version. Maven Central releases are immutable: do not upload a second
deployment for coordinates that Central has already accepted.

The website keeps `1.6.0` as its stable version until the `1.7.0` publication is independently
confirmed. After that confirmation, update `website/src/data/release.json` in the website change;
the Spring page is labeled as upcoming until then.
