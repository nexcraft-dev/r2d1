import org.gradle.api.tasks.testing.Test

plugins {
    id("com.vanniktech.maven.publish")
}

description = "JDBC IndexStore with built-in H2, HSQLDB, and SQLite support"

val javaToolchainVersion =
    providers.gradleProperty("r2d1.javaToolchainVersion").map(String::toInt).orElse(21)

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (javaToolchainVersion.get() >= 25) {
            excludeTags("java-before-25")
        } else {
            excludeTags("java-25")
        }
    }
}

dependencies {
    api(project(":r2d1-core"))
    api("org.jspecify:jspecify:1.0.0")

    testImplementation(testFixtures(project(":r2d1-core")))
    testImplementation("com.h2database:h2:2.5.250")
    testImplementation("org.hsqldb:hsqldb:2.7.4")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
}
