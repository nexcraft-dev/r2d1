plugins {
    `java-test-fixtures`
}

description = "Framework-independent public API and abstractions"

dependencies {
    api("org.jspecify:jspecify:1.0.0")

    testFixturesImplementation(platform("org.junit:junit-bom:5.14.4"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter")
    testFixturesImplementation("org.assertj:assertj-core:3.27.7")
}
