# R2D1 Release

R2D1 publishes its reusable Java libraries to Maven Central through the Sonatype Central Portal.
The release workflow publishes these four artifacts with the same version:

- `dev.nexcraft:r2d1-core`
- `dev.nexcraft:r2d1-d1`
- `dev.nexcraft:r2d1-r2`
- `dev.nexcraft:r2d1-jdbc`

The repository root and `r2d1-integration-tests` are not published artifacts.

## Required GitHub Secrets

Configure these repository secrets before the first release:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `GPG_PRIVATE_KEY`
- `GPG_PASSPHRASE`

The expected public signing key ID is `3D84F8C994DA0DBB`. Gradle derives the signing key ID from the
private key; the workflow does not pass `signingInMemoryKeyId` because that property accepts only an
eight-character short key ID.

The workflow passes the values to Gradle as in-memory project properties. Secret values must never
be committed, copied into `gradle.properties`, or printed in workflow logs.

## Version and tag

Development builds use the default `0.1.0-SNAPSHOT` version. A release supplies the version through
the `r2d1.version` Gradle property. The release tag and supplied version must match exactly:

```text
v1.0.0 -> 1.0.0
```

Snapshot versions and malformed tags are rejected before publication.

## Release flow

The `maven-central-release.yml` workflow runs only for a semantic-version Git tag. It checks out the
tag, runs the complete verification build, validates the release properties, publishes to Maven Local
to verify the generated artifacts, and then invokes `publishToMavenCentral`. The Vanniktech publisher
waits for Central validation and automatically releases a validated deployment.

The workflow does not run for ordinary branch pushes or pull requests.

Before the first release, review the generated POMs and artifacts locally:

```shell
./gradlew clean build
./gradlew -Pr2d1.version=1.0.0 publishToMavenLocal
```

Inspect the four Maven Local directories under `~/.m2/repository/dev/nexcraft/` and confirm each
contains the main JAR, sources JAR, javadoc JAR, POM, and expected module dependencies.
The release workflow additionally requires detached ASCII-armored signatures for all four files
before it starts the Maven Central upload.

## Starting v1.0.0

After merging the release configuration and confirming all required secrets:

```shell
git tag v1.0.0
git push origin v1.0.0
```

That tag push is the production action. It starts the GitHub Actions workflow and can upload and
automatically publish the deployment to Maven Central. Do not repeat the tag or upload a second
deployment for the same coordinates; Maven Central releases are immutable.

## Failed releases

If Central validation fails, inspect the deployment validation details in the Central Portal and fix
the repository configuration locally. Do not reuse the failed deployment or overwrite the same
version. Create a new corrected deployment only after choosing an unreleased version according to the
project's release policy.
