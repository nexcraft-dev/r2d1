import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessTask
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

allprojects {
    group = "dev.nexcraft"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
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
}
