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

## Version and tag policy

Development builds use the default `0.1.0-SNAPSHOT` version. A release supplies the version through
the `r2d1.version` Gradle property.

A push to `main` creates and publishes the next release automatically. If no release tag exists, the
workflow starts at `v1.0.0`. Later `main` changes increment the minor version and reset the patch
version:

```text
no release tag -> v1.0.0
v1.0.0 -> v1.1.0
v1.1.0 -> v1.2.0
```

Major versions are selected manually. Push the chosen major tag, such as `v2.0.0`, on the intended
`main` commit. The tag-triggered workflow publishes that exact version, and later `main` changes
continue with `v2.1.0`, `v2.2.0`, and so on.

On a retried run, the workflow reuses a valid release tag that already points at the same commit
instead of incrementing the version again. Snapshot versions and malformed manual tags are rejected
before publication.

## Release flow

The `maven-central-release.yml` workflow runs for pushes to `main` and manually pushed semantic-version
tags. It resolves the release version, runs the complete verification build, validates the release
properties, and publishes to Maven Local to verify the generated artifacts and signatures. For an
automatic minor release, it then creates the resolved tag on the verified commit. Finally, it invokes
`publishToMavenCentral`; the Vanniktech publisher waits for Central validation and automatically
releases a validated deployment.

The workflow does not run for feature branch pushes or pull requests. Release runs are serialized so
concurrent `main` changes cannot claim the same version.

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

After confirming all required secrets, merge the release configuration into `main`. That `main` push
creates `v1.0.0` and starts the production publication automatically. No manual tag command is needed
for the initial release or later minor releases.

For a future major release, tag the selected `main` commit explicitly:

```shell
git tag -a v2.0.0 -m "Release v2.0.0"
git push origin v2.0.0
```

The tag push starts the same production workflow for the explicit major version. Do not repeat a tag
or upload a second deployment for coordinates that Maven Central has already published; published
releases are immutable.

## Failed releases

If a run fails, first check whether Central published any coordinates. A rerun for the same commit
reuses its existing tag. If Central already published the version, do not retry or overwrite it;
published releases are immutable. Otherwise, inspect the deployment validation details in the Central
Portal and rerun only after correcting the failure.
