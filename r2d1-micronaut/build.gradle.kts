import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension

plugins {
    id("io.micronaut.library")
    id("com.vanniktech.maven.publish")
}

description = "Micronaut 5 configuration and dependency injection integration"

val optionalAdapterTest = sourceSets.create("optionalAdapterTest")
optionalAdapterTest.compileClasspath += sourceSets.main.get().output
optionalAdapterTest.runtimeClasspath += optionalAdapterTest.output + optionalAdapterTest.compileClasspath

val nativeH2Test = sourceSets.create("nativeH2Test")
nativeH2Test.compileClasspath += sourceSets.main.get().output
nativeH2Test.runtimeClasspath += nativeH2Test.output + nativeH2Test.compileClasspath

micronaut {
    version.set("5.1.5")
    processing {
        incremental.set(true)
        module.set(project.name)
        group.set(project.group.toString())
        annotations.add("dev.nexcraft.r2d1.micronaut.*")
    }
}

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

dependencies {
    api(project(":r2d1-core"))
    api("org.jspecify:jspecify:1.0.0")
    api("io.micronaut:micronaut-context")

    compileOnly(project(":r2d1-jdbc"))
    compileOnly(project(":r2d1-d1"))
    compileOnly(project(":r2d1-r2"))

    testImplementation(project(":r2d1-jdbc"))
    testImplementation(project(":r2d1-d1"))
    testImplementation(project(":r2d1-r2"))
    testImplementation("com.h2database:h2:2.5.250")

    add(optionalAdapterTest.implementationConfigurationName, platform("org.junit:junit-bom:5.14.4"))
    add(optionalAdapterTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
    add(optionalAdapterTest.implementationConfigurationName, "org.assertj:assertj-core:3.27.7")
    add(optionalAdapterTest.implementationConfigurationName, project(":r2d1-core"))
    add(
        optionalAdapterTest.implementationConfigurationName,
        platform("io.micronaut.platform:micronaut-platform:5.1.5"),
    )
    add(optionalAdapterTest.implementationConfigurationName, "io.micronaut:micronaut-context")
    add(
        optionalAdapterTest.runtimeOnlyConfigurationName,
        "org.junit.platform:junit-platform-launcher",
    )

    add(nativeH2Test.implementationConfigurationName, platform("org.junit:junit-bom:5.14.4"))
    add(nativeH2Test.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
    add(nativeH2Test.implementationConfigurationName, "org.assertj:assertj-core:3.27.7")
    add(nativeH2Test.implementationConfigurationName, project(":r2d1-core"))
    add(nativeH2Test.implementationConfigurationName, project(":r2d1-jdbc"))
    add(nativeH2Test.implementationConfigurationName, "com.h2database:h2:2.5.250")
    add(
        nativeH2Test.implementationConfigurationName,
        platform("io.micronaut.platform:micronaut-platform:5.1.5"),
    )
    add(nativeH2Test.implementationConfigurationName, "io.micronaut:micronaut-context")
    add(nativeH2Test.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}

val optionalAdapterTestTask =
    tasks.register<Test>("optionalAdapterTest") {
        description = "Tests safe startup diagnostics without optional R2D1 adapters."
        group = "verification"
        testClassesDirs = optionalAdapterTest.output.classesDirs
        classpath = optionalAdapterTest.runtimeClasspath
        shouldRunAfter(tasks.named("test"))
    }

tasks.named("check") {
    dependsOn(optionalAdapterTestTask)
}

val nativeH2JvmTest =
    tasks.register<Test>("nativeH2JvmTest") {
        description = "Runs the H2-only JVM precursor for the native test binary."
        group = "verification"
        testClassesDirs = nativeH2Test.output.classesDirs
        classpath = nativeH2Test.runtimeClasspath
    }

extensions.configure<GraalVMExtension> {
    registerTestBinary("h2Test") {
        usingSourceSet(nativeH2Test)
        forTestTask(nativeH2JvmTest)
    }
}

configurations
    .matching {
        it.name == "nativeImageH2TestClasspath" ||
            it.name == "nativeImageH2TestClasspathInternal"
    }
    .configureEach {
        setExtendsFrom(
            extendsFrom.filterNot {
                it.name == "testImplementation" || it.name == "testRuntimeOnly"
            } +
                listOf(
                    configurations[nativeH2Test.implementationConfigurationName],
                    configurations[nativeH2Test.runtimeOnlyConfigurationName],
                )
        )
    }

gradle.projectsEvaluated {
    tasks.named("nativeTestCompile") {
        enabled = false
    }
    tasks.named("generateTestResourcesConfigFile") {
        enabled = false
    }
    tasks.named("nativeTest") {
        setDependsOn(listOf("nativeH2Test"))
        enabled = false
    }
}
