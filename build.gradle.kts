import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessTask
import java.util.jar.JarFile
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    id("com.diffplug.spotless") version "8.10.2" apply false
    id("io.micronaut.library") version "5.0.2" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

abstract class SpotlessSerializationService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    override fun close() {}
}

val spotlessSerializationService =
    gradle.sharedServices.registerIfAbsent(
        "spotlessSerializationService",
        SpotlessSerializationService::class,
    ) {
        maxParallelUsages.set(1)
    }

val javaToolchainVersion =
    providers.gradleProperty("r2d1.javaToolchainVersion").map(String::toInt).orElse(21)

val r2d1Version =
    providers.gradleProperty("r2d1.version").orElse("1.7.0-SNAPSHOT")

val publicPublicationProjects =
    linkedMapOf(
        ":r2d1" to "r2d1",
        ":r2d1-filesystem" to "r2d1-filesystem",
        ":r2d1-jdbc" to "r2d1-jdbc",
        ":r2d1-micronaut" to "r2d1-micronaut",
        ":r2d1-spring-boot-autoconfigure" to "r2d1-spring-boot-autoconfigure",
        ":r2d1-spring-boot-starter" to "r2d1-spring-boot-starter",
    )

val publicationNames =
    mapOf(
        "r2d1" to "R2D1",
        "r2d1-filesystem" to "R2D1 Filesystem DocumentStore",
        "r2d1-jdbc" to "R2D1 JDBC IndexStore",
        "r2d1-micronaut" to "R2D1 Micronaut 5 Integration",
        "r2d1-spring-boot-autoconfigure" to "R2D1 Spring Boot Autoconfigure",
        "r2d1-spring-boot-starter" to "R2D1 Spring Boot Starter",
    )

val verificationRepositoryDirectory =
    layout.buildDirectory.dir("verification-repository")
val verificationVersion = r2d1Version.get()
val verificationConsumerDirectory = layout.buildDirectory.dir("published-consumer")
val verificationGradleWrapper = file("gradlew")

data class MavenPublicationCoordinate(
    val projectPath: String,
    val groupId: String,
    val artifactId: String,
    val version: String,
)

allprojects {
    group = "dev.nexcraft"
    version = r2d1Version.get()
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(javaToolchainVersion.map(JavaLanguageVersion::of))
        }
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.15"
    }

    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.36.1")
        }
    }

    tasks.withType<SpotlessTask>().configureEach {
        usesService(spotlessSerializationService)
    }

    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:5.14.4"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testImplementation", "org.assertj:assertj-core:3.27.7")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        if (javaToolchainVersion.get() >= 25) {
            // Test dependencies load native libraries and use legacy Unsafe on Java 25.
            jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "--sun-misc-unsafe-memory-access=allow",
            )
        }
    }

    tasks.named<Test>("test") {
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required = true
            html.required = true
            csv.required = false
        }
    }

    tasks.withType<Javadoc>().configureEach {
        exclude("**/internal/**")
    }

    plugins.withId("com.vanniktech.maven.publish") {
        afterEvaluate {
            val signingKey = providers.gradleProperty("signingInMemoryKey")
            val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword")
            extensions.configure<MavenPublishBaseExtension> {
                coordinates(
                    groupId = "dev.nexcraft",
                    artifactId = project.name,
                    version = project.version.toString(),
                )
                publishToMavenCentral(automaticRelease = true)
                if (signingKey.isPresent && signingPassword.isPresent) {
                    signAllPublications()
                }
                pom {
                    name.set(publicationNames[project.name] ?: project.name)
                    description.set(project.description ?: project.name)
                    url.set("https://github.com/nexcraft-dev/r2d1")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("sean")
                            name.set("Sean")
                            email.set("dev@nexcraft.dev")
                        }
                    }
                    scm {
                        url.set("https://github.com/nexcraft-dev/r2d1")
                        connection.set("scm:git:git://github.com/nexcraft-dev/r2d1.git")
                        developerConnection.set("scm:git:ssh://git@github.com/nexcraft-dev/r2d1.git")
                    }
                }
            }
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "verification"
                        url = verificationRepositoryDirectory.get().asFile.toURI()
                    }
                }
            }
        }
    }

    tasks.configureEach {
        if (name.startsWith("publish") && name.contains("MavenCentral")) {
            doFirst {
                check(!project.version.toString().endsWith("-SNAPSHOT")) {
                    "Maven Central publication requires a non-SNAPSHOT version"
                }
                check(providers.gradleProperty("mavenCentralUsername").isPresent) {
                    "mavenCentralUsername Gradle property is required for Maven Central publication"
                }
                check(providers.gradleProperty("mavenCentralPassword").isPresent) {
                    "mavenCentralPassword Gradle property is required for Maven Central publication"
                }
                check(providers.gradleProperty("signingInMemoryKey").isPresent) {
                    "signingInMemoryKey Gradle property is required for Maven Central publication"
                }
                check(providers.gradleProperty("signingInMemoryKeyPassword").isPresent) {
                    "signingInMemoryKeyPassword Gradle property is required for Maven Central publication"
                }
            }
        }
    }

    plugins.withId("java-test-fixtures") {
        val javaComponent = components["java"] as AdhocComponentWithVariants
        configurations.findByName("testFixturesApiElements")?.let { configuration ->
            javaComponent.withVariantsFromConfiguration(configuration) {
                skip()
            }
        }
        configurations.findByName("testFixturesRuntimeElements")?.let { configuration ->
            javaComponent.withVariantsFromConfiguration(configuration) {
                skip()
            }
        }
    }
}

gradle.projectsEvaluated {
    subprojects
        .filter { it.plugins.hasPlugin("java-test-fixtures") }
        .forEach { project ->
            val javaComponent = project.components["java"] as AdhocComponentWithVariants
            project.configurations.findByName("testFixturesSourcesElements")?.let { configuration ->
                javaComponent.withVariantsFromConfiguration(configuration) {
                    skip()
                }
            }
        }

    publicPublicationProjects.forEach { (projectPath, artifactId) ->
        val publicationTask =
            project(projectPath).tasks.named("publishMavenPublicationToVerificationRepository")
        publicationTask.configure {
            mustRunAfter(":prepareVerificationRepository")
        }
        check(artifactId == project(projectPath).name) {
            "The public publication allowlist must use the project artifact name"
        }
    }
}

val validateMavenCentralPublicationSurface =
    tasks.register("validateMavenCentralPublicationSurface") {
        group = "publishing"
        description = "Fails if any project creates a Maven publication outside the public allowlist."
        doLast {
            val actual =
                subprojects
                    .flatMap { project ->
                        project.extensions
                            .findByType(PublishingExtension::class.java)
                            ?.publications
                            ?.withType(MavenPublication::class.java)
                            ?.map { publication ->
                                MavenPublicationCoordinate(
                                    project.path,
                                    publication.groupId,
                                    publication.artifactId,
                                    publication.version,
                                )
                            }
                            ?: emptyList()
                    }
                    .sortedBy { it.projectPath }
            val expected =
                publicPublicationProjects
                    .map { (projectPath, artifactId) ->
                        MavenPublicationCoordinate(
                            projectPath,
                            "dev.nexcraft",
                            artifactId,
                            verificationVersion,
                        )
                    }
                    .sortedBy { it.projectPath }
            check(actual == expected) {
                "Unexpected Maven publication surface. Expected $expected but found $actual"
            }
        }
    }

val prepareVerificationRepository =
    tasks.register("prepareVerificationRepository") {
        group = "verification"
        description = "Creates an empty temporary Maven repository for publication verification."
        doLast {
            val directory = verificationRepositoryDirectory.get().asFile
            check(directory != rootProject.projectDir) {
                "Refusing to use the repository root as the verification repository"
            }
            directory.deleteRecursively()
            check(directory.mkdirs() || directory.isDirectory) {
                "Could not create verification repository $directory"
            }
        }
    }

val publishPublicationsToVerificationRepository =
    tasks.register("publishPublicationsToVerificationRepository") {
        group = "verification"
        description = "Publishes the six public artifacts to a temporary file Maven repository."
        dependsOn(prepareVerificationRepository)
        dependsOn(
            publicPublicationProjects.keys.map { projectPath ->
                "$projectPath:publishMavenPublicationToVerificationRepository"
            }
        )
    }

val verifyPublishedConsumer =
    tasks.register("verifyPublishedConsumer") {
        group = "verification"
        description =
            "Verifies the published artifact surface, metadata, thin JAR contents, and isolated consumers."
        dependsOn(validateMavenCentralPublicationSurface)
        dependsOn(publishPublicationsToVerificationRepository)
        doLast {
            val repository = verificationRepositoryDirectory.get().asFile
            val version = verificationVersion
            val coordinateRoot = repository.resolve("dev/nexcraft")
            val expectedArtifactIds = publicPublicationProjects.values.toSet()
            val actualArtifactIds =
                coordinateRoot.listFiles()
                    ?.filter { it.isDirectory }
                    ?.map { it.name }
                    ?.toSet()
                    ?: emptySet()
            check(actualArtifactIds == expectedArtifactIds) {
                "Expected exactly $expectedArtifactIds in $coordinateRoot but found $actualArtifactIds"
            }

            val oldCoordinates = listOf("r2d1-core", "r2d1-r2", "r2d1-d1")
            val requiredEntries =
                mapOf(
                    "r2d1" to
                        listOf(
                            "dev/nexcraft/r2d1/R2D1.class",
                            "dev/nexcraft/r2d1/r2/R2DocumentStore.class",
                            "dev/nexcraft/r2d1/d1/D1IndexStore.class",
                        ),
                    "r2d1-filesystem" to
                        listOf(
                            "dev/nexcraft/r2d1/filesystem/FileSystemDocumentStore.class",
                        ),
                    "r2d1-jdbc" to
                        listOf(
                            "dev/nexcraft/r2d1/jdbc/JdbcExecution.class",
                            "dev/nexcraft/r2d1/jdbc/JdbcIndexStore.class",
                        ),
                    "r2d1-micronaut" to
                        listOf(
                            "dev/nexcraft/r2d1/micronaut/R2D1Configuration.class",
                            "dev/nexcraft/r2d1/micronaut/internal/R2Factory.class",
                        ),
                    "r2d1-spring-boot-autoconfigure" to
                        listOf(
                            "dev/nexcraft/r2d1/spring/R2D1AutoConfiguration.class",
                            "dev/nexcraft/r2d1/spring/R2D1Properties.class",
                            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                            "META-INF/spring-configuration-metadata.json",
                        ),
                    "r2d1-spring-boot-starter" to emptyList(),
                )
            val classOwners = mutableMapOf<String, String>()

            expectedArtifactIds.forEach { artifactId ->
                val versionDirectory = repository.resolve("dev/nexcraft/$artifactId/$version")
                check(versionDirectory.isDirectory) {
                    "Missing published version directory $versionDirectory"
                }
                val publishedFiles = versionDirectory.listFiles()?.toList() ?: emptyList()
                fun publishedFile(description: String, matches: (String) -> Boolean): java.io.File {
                    val matchesFiles = publishedFiles.filter { matches(it.name) }
                    check(matchesFiles.size == 1) {
                        "Expected one $description for $artifactId but found ${matchesFiles.map { it.name }}"
                    }
                    return matchesFiles.single()
                }
                val mainJar =
                    publishedFile("main JAR") {
                        it.startsWith("$artifactId-") &&
                            it.endsWith(".jar") &&
                            !it.endsWith("-sources.jar") &&
                            !it.endsWith("-javadoc.jar")
                    }
                val sourcesJar = publishedFile("sources JAR") { it.endsWith("-sources.jar") }
                val javadocJar = publishedFile("Javadoc JAR") { it.endsWith("-javadoc.jar") }
                val pomFile = publishedFile("POM") { it.endsWith(".pom") }
                val moduleFile = publishedFile("Gradle Module Metadata") { it.endsWith(".module") }

                val pom = pomFile.readText()
                val module = moduleFile.readText()
                check(oldCoordinates.none { coordinate -> pom.contains(coordinate) }) {
                    "$artifactId POM contains a removed internal coordinate"
                }
                check(oldCoordinates.none { coordinate -> module.contains(coordinate) }) {
                    "$artifactId Gradle Module Metadata contains a removed internal coordinate"
                }
                if (artifactId == "r2d1-jdbc") {
                    check(pom.contains("<artifactId>r2d1</artifactId>")) {
                        "r2d1-jdbc POM does not reference dev.nexcraft:r2d1"
                    }
                    check(module.contains("\"module\": \"r2d1\"") || module.contains("\"module\":\"r2d1\"")) {
                        "r2d1-jdbc Gradle Module Metadata does not reference dev.nexcraft:r2d1"
                    }
                }
                if (artifactId == "r2d1-micronaut") {
                    check(pom.contains("<artifactId>r2d1</artifactId>")) {
                        "r2d1-micronaut POM does not reference dev.nexcraft:r2d1"
                    }
                    check(module.contains("\"module\": \"r2d1\"") || module.contains("\"module\":\"r2d1\"")) {
                        "r2d1-micronaut Gradle Module Metadata does not reference dev.nexcraft:r2d1"
                    }
                    check(!pom.contains("<artifactId>r2d1-jdbc</artifactId>")) {
                        "r2d1-micronaut POM leaks its compileOnly JDBC adapter"
                    }
                    check(!module.contains("r2d1-jdbc")) {
                        "r2d1-micronaut Gradle Module Metadata leaks its compileOnly JDBC adapter"
                    }
                    check(!pom.contains("<groupId>io.micronaut</groupId>")) {
                        "r2d1-micronaut POM leaks its compileOnly Micronaut framework"
                    }
                    check(!module.contains("io.micronaut")) {
                        "r2d1-micronaut Gradle Module Metadata leaks its compileOnly Micronaut framework"
                    }
                }
                if (artifactId == "r2d1-spring-boot-autoconfigure") {
                    check(pom.contains("<artifactId>r2d1</artifactId>")) {
                        "r2d1-spring-boot-autoconfigure POM does not reference dev.nexcraft:r2d1"
                    }
                    check(module.contains("r2d1")) {
                        "r2d1-spring-boot-autoconfigure Gradle Module Metadata does not reference dev.nexcraft:r2d1"
                    }
                    check(!pom.contains("<artifactId>r2d1-filesystem</artifactId>")) {
                        "r2d1-spring-boot-autoconfigure POM leaks its compileOnly filesystem adapter"
                    }
                    check(!pom.contains("<artifactId>r2d1-jdbc</artifactId>")) {
                        "r2d1-spring-boot-autoconfigure POM leaks its compileOnly JDBC adapter"
                    }
                    check(!module.contains("r2d1-filesystem")) {
                        "r2d1-spring-boot-autoconfigure Gradle Module Metadata leaks its compileOnly filesystem adapter"
                    }
                    check(!module.contains("r2d1-jdbc")) {
                        "r2d1-spring-boot-autoconfigure Gradle Module Metadata leaks its compileOnly JDBC adapter"
                    }
                    check(!pom.contains("<groupId>org.springframework.boot</groupId>")) {
                        "r2d1-spring-boot-autoconfigure POM leaks its compileOnly Spring Boot framework"
                    }
                    check(!module.contains("org.springframework.boot")) {
                        "r2d1-spring-boot-autoconfigure Gradle Module Metadata leaks its compileOnly Spring Boot framework"
                    }
                }
                if (artifactId == "r2d1-spring-boot-starter") {
                    check(pom.contains("<artifactId>r2d1-spring-boot-autoconfigure</artifactId>")) {
                        "r2d1-spring-boot-starter POM does not reference its auto-configuration module"
                    }
                    check(module.contains("r2d1-spring-boot-autoconfigure")) {
                        "r2d1-spring-boot-starter Gradle Module Metadata does not reference its auto-configuration module"
                    }
                    check(pom.contains("<artifactId>spring-boot-starter</artifactId>")) {
                        "r2d1-spring-boot-starter POM does not reference spring-boot-starter"
                    }
                    check(!pom.contains("<artifactId>r2d1-filesystem</artifactId>")) {
                        "r2d1-spring-boot-starter POM leaks the filesystem adapter"
                    }
                    check(!pom.contains("<artifactId>r2d1-jdbc</artifactId>")) {
                        "r2d1-spring-boot-starter POM leaks the JDBC adapter"
                    }
                    check(!module.contains("r2d1-filesystem")) {
                        "r2d1-spring-boot-starter Gradle Module Metadata leaks the filesystem adapter"
                    }
                    check(!module.contains("r2d1-jdbc")) {
                        "r2d1-spring-boot-starter Gradle Module Metadata leaks the JDBC adapter"
                    }
                }

                check(sourcesJar.isFile && javadocJar.isFile) {
                    "Published source or Javadoc artifact is missing for $artifactId"
                }
                JarFile(mainJar).use { jar ->
                    val entries = jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                    check(entries.size == entries.toSet().size) {
                        "$artifactId contains duplicate JAR entries"
                    }
                    requiredEntries.getValue(artifactId).forEach { entry ->
                        check(entry in entries) {
                            "$artifactId is missing required class entry $entry"
                        }
                    }
                    entries.filter { it.endsWith(".class") }.forEach { entry ->
                        val previousOwner = classOwners.put(entry, artifactId)
                        check(previousOwner == null) {
                            "Class entry $entry is present in both $previousOwner and $artifactId"
                        }
                    }
                }
            }

            val repositoryUri = repository.toURI()
            val consumerRoot = verificationConsumerDirectory.get().asFile
            consumerRoot.deleteRecursively()

            fun createConsumer(root: java.io.File, useGradleMetadata: Boolean) {
                root.mkdirs()
                val repositoryBlock =
                    if (useGradleMetadata) {
                        """
                        maven {
                            url = uri("$repositoryUri")
                        }
                        """.trimIndent()
                    } else {
                        """
                        maven {
                            url = uri("$repositoryUri")
                            metadataSources {
                                mavenPom()
                                artifact()
                            }
                        }
                        """.trimIndent()
                    }
                root.resolve("settings.gradle.kts").writeText(
                    """
                    pluginManagement {
                        repositories {
                            gradlePluginPortal()
                        }
                    }

                    dependencyResolutionManagement {
                        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                        repositories {
                            $repositoryBlock
                            mavenCentral()
                        }
                    }

                    rootProject.name = "r2d1-published-consumer"
                    include(
                        "core",
                        "filesystem",
                        "jdbc",
                        "micronaut",
                        "spring-autoconfigure",
                        "spring-starter",
                        "combined",
                    )
                    """.trimIndent()
                )

                val springConsumerSource =
                    """
                    package consumer;

                    import dev.nexcraft.r2d1.spring.R2D1AutoConfiguration;
                    import dev.nexcraft.r2d1.spring.R2D1Properties;

                    public final class Main {
                      public static void main(String[] args) {
                        require(R2D1AutoConfiguration.class);
                        require(R2D1Properties.class);
                      }

                      private static void require(Class<?> type) {
                        if (type.getName().isBlank()) {
                          throw new AssertionError(type.getName());
                        }
                      }
                    }
                    """.trimIndent()

                val sourceByVariant =
                    mapOf(
                        "core" to
                            """
                            package consumer;

                            import dev.nexcraft.r2d1.R2D1;
                            import dev.nexcraft.r2d1.d1.D1IndexStore;
                            import dev.nexcraft.r2d1.r2.R2DocumentStore;

                            public final class Main {
                              public static void main(String[] args) {
                                require(R2D1.class);
                                require(R2DocumentStore.class);
                                require(D1IndexStore.class);
                              }

                              private static void require(Class<?> type) {
                                if (!type.getName().startsWith("dev.nexcraft.r2d1.")) {
                                  throw new AssertionError(type.getName());
                                }
                              }
                            }
                            """.trimIndent(),
                        "jdbc" to
                            """
                            package consumer;

                            import dev.nexcraft.r2d1.jdbc.JdbcExecution;
                            import dev.nexcraft.r2d1.jdbc.JdbcExecutionConfig;
                            import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;

                            public final class Main {
                              public static void main(String[] args) {
                                require(JdbcExecution.class);
                                require(JdbcExecutionConfig.class);
                                require(JdbcIndexStore.class);
                              }

                              private static void require(Class<?> type) {
                                if (!type.getName().startsWith("dev.nexcraft.r2d1.jdbc.")) {
                                  throw new AssertionError(type.getName());
                                }
                              }
                            }
                            """.trimIndent(),
                        "filesystem" to
                            """
                            package consumer;

                            import dev.nexcraft.r2d1.filesystem.FileSystemDocumentStore;

                            public final class Main {
                              public static void main(String[] args) {
                                require(FileSystemDocumentStore.class);
                              }

                              private static void require(Class<?> type) {
                                if (type.getName().isBlank()) {
                                  throw new AssertionError(type.getName());
                                }
                              }
                            }
                            """.trimIndent(),
                        "micronaut" to
                            """
                            package consumer;

                            import dev.nexcraft.r2d1.R2D1;
                            import dev.nexcraft.r2d1.d1.D1IndexStore;
                            import dev.nexcraft.r2d1.micronaut.R2D1Configuration;
                            import dev.nexcraft.r2d1.r2.R2DocumentStore;
                            import io.micronaut.context.ApplicationContext;

                            public final class Main {
                              public static void main(String[] args) {
                                try (ApplicationContext context = ApplicationContext.run()) {
                                  require(context.getClass());
                                  require(R2D1.class);
                                  require(R2D1Configuration.class);
                                  require(R2DocumentStore.class);
                                  require(D1IndexStore.class);
                                }
                              }

                              private static void require(Class<?> type) {
                                if (type.getName().isBlank()) {
                                  throw new AssertionError(type.getName());
                                }
                              }
                            }
                            """.trimIndent(),
                        "spring-autoconfigure" to springConsumerSource,
                        "spring-starter" to springConsumerSource,
                        "combined" to
                            """
                            package consumer;

                            import dev.nexcraft.r2d1.R2D1;
                            import dev.nexcraft.r2d1.d1.D1IndexStore;
                            import dev.nexcraft.r2d1.filesystem.FileSystemDocumentStore;
                            import dev.nexcraft.r2d1.jdbc.JdbcIndexStore;
                            import dev.nexcraft.r2d1.micronaut.R2D1Configuration;
                            import dev.nexcraft.r2d1.r2.R2DocumentStore;
                            import dev.nexcraft.r2d1.spring.R2D1AutoConfiguration;

                            public final class Main {
                              public static void main(String[] args) {
                                require(R2D1.class);
                                require(R2DocumentStore.class);
                                require(D1IndexStore.class);
                                require(FileSystemDocumentStore.class);
                                require(JdbcIndexStore.class);
                                require(R2D1Configuration.class);
                                require(R2D1AutoConfiguration.class);
                              }

                              private static void require(Class<?> type) {
                                if (type.getName().isBlank()) {
                                  throw new AssertionError(type.getName());
                                }
                              }
                            }
                            """.trimIndent(),
                    )
                val dependencyCoordinates =
                    mapOf(
                        "core" to listOf("dev.nexcraft:r2d1:$version"),
                        "filesystem" to listOf("dev.nexcraft:r2d1-filesystem:$version"),
                        "jdbc" to listOf("dev.nexcraft:r2d1-jdbc:$version"),
                        "micronaut" to
                            listOf(
                                "dev.nexcraft:r2d1-micronaut:$version",
                                "io.micronaut:micronaut-context:5.1.15",
                            ),
                        "spring-autoconfigure" to
                            listOf(
                                "dev.nexcraft:r2d1-spring-boot-autoconfigure:$version",
                                "org.springframework.boot:spring-boot-autoconfigure:4.0.6",
                            ),
                        "spring-starter" to listOf("dev.nexcraft:r2d1-spring-boot-starter:$version"),
                        "combined" to
                            listOf(
                                "dev.nexcraft:r2d1:$version",
                                "dev.nexcraft:r2d1-filesystem:$version",
                                "dev.nexcraft:r2d1-jdbc:$version",
                                "dev.nexcraft:r2d1-micronaut:$version",
                                "dev.nexcraft:r2d1-spring-boot-starter:$version",
                                "io.micronaut:micronaut-context:5.1.15",
                            ),
                    )

                sourceByVariant.forEach { (variant, source) ->
                    val variantRoot = root.resolve(variant)
                    variantRoot.mkdirs()
                    variantRoot.resolve("build.gradle.kts").writeText(
                        """
                        import org.gradle.api.tasks.JavaExec

                        plugins {
                            java
                        }

                        dependencies {
                            ${dependencyCoordinates.getValue(variant).joinToString("\n") { "implementation(\"$it\")" }}
                        }

                        tasks.register<JavaExec>("verifyRuntime") {
                            dependsOn(tasks.named("classes"))
                            classpath = sourceSets.main.get().runtimeClasspath
                            mainClass.set("consumer.Main")
                        }
                        """.trimIndent()
                    )
                    val sourceDirectory = variantRoot.resolve("src/main/java/consumer")
                    sourceDirectory.mkdirs()
                    sourceDirectory.resolve("Main.java").writeText(source)
                }
            }

            val consumerRoots =
                listOf(
                    consumerRoot.resolve("gradle-metadata") to true,
                    consumerRoot.resolve("pom-only") to false,
                )
            consumerRoots.forEach { (root, useGradleMetadata) ->
                createConsumer(root, useGradleMetadata)
                val process =
                    ProcessBuilder(
                        verificationGradleWrapper.absolutePath,
                            "--no-daemon",
                            "--console=plain",
                            ":core:verifyRuntime",
                            ":jdbc:verifyRuntime",
                            ":micronaut:verifyRuntime",
                            ":spring-autoconfigure:verifyRuntime",
                            ":spring-starter:verifyRuntime",
                            ":combined:verifyRuntime",
                        )
                        .directory(root)
                        .inheritIO()
                        .start()
                check(process.waitFor() == 0) {
                    "Published consumer verification failed for ${root.name}"
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(validateMavenCentralPublicationSurface)
    dependsOn(verifyPublishedConsumer)
}

tasks.register("validateMavenCentralConfiguration") {
    group = "publishing"
    description = "Validates release version and Maven Central credentials without uploading artifacts."
    doLast {
        check(!version.toString().endsWith("-SNAPSHOT")) {
            "Maven Central release version must not be a SNAPSHOT"
        }
        listOf(
                "mavenCentralUsername",
                "mavenCentralPassword",
                "signingInMemoryKey",
                "signingInMemoryKeyPassword",
            )
            .forEach { propertyName ->
                check(providers.gradleProperty(propertyName).isPresent) {
                    "$propertyName Gradle property is required for Maven Central publication"
                }
            }
    }
}
