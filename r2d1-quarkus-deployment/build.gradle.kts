plugins {
    id("com.vanniktech.maven.publish")
}

description = "Quarkus build-time integration for R2D1"

val optionalAdapterTest = sourceSets.create("optionalAdapterTest")

dependencies {
    implementation(platform("io.quarkus:quarkus-bom:3.39.5"))
    implementation(project(":r2d1-quarkus"))
    implementation("io.quarkus:quarkus-arc-deployment")
    testImplementation("io.quarkus:quarkus-junit-internal")
    testImplementation(project(":r2d1-jdbc"))
    testImplementation(project(":r2d1-filesystem"))
    testImplementation("com.h2database:h2:2.5.250")

    add(optionalAdapterTest.implementationConfigurationName, platform("io.quarkus:quarkus-bom:3.39.5"))
    add(optionalAdapterTest.implementationConfigurationName, platform("org.junit:junit-bom:5.14.4"))
    add(optionalAdapterTest.implementationConfigurationName, project(":r2d1-quarkus"))
    add(optionalAdapterTest.implementationConfigurationName, "io.quarkus:quarkus-arc-deployment")
    add(optionalAdapterTest.implementationConfigurationName, "io.quarkus:quarkus-junit-internal")
    add(optionalAdapterTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
    add(optionalAdapterTest.implementationConfigurationName, "org.assertj:assertj-core:3.27.7")
    add(optionalAdapterTest.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}

tasks.test {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    maxHeapSize = "1g"
}

val optionalAdapterTestTask = tasks.register<Test>("optionalAdapterTest") {
    testClassesDirs = optionalAdapterTest.output.classesDirs
    classpath = optionalAdapterTest.runtimeClasspath + sourceSets.main.get().output
    systemProperty("TEST_TO_MAIN_MAPPINGS", "classes/java/optionalAdapterTest:classes/java/main")
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    maxHeapSize = "1g"
}
tasks.check { dependsOn(optionalAdapterTestTask) }
