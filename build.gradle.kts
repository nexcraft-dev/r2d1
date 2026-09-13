import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessTask
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    id("com.diffplug.spotless") version "8.10.2" apply false
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
    providers.gradleProperty("r2d1.version").orElse("0.1.0-SNAPSHOT")

val publicationNames =
    mapOf(
        "r2d1-core" to "R2D1 Core",
        "r2d1-d1" to "R2D1 Cloudflare D1 Integration",
        "r2d1-r2" to "R2D1 Cloudflare R2 Integration",
        "r2d1-jdbc" to "R2D1 JDBC IndexStore",
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
